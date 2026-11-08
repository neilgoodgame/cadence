package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.ActivityMergeOrchestrator;
import com.cadence.api.activities.ActivityMergeService;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.ActivitySourceService;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.ActivityUnmergeService;
import com.cadence.api.activities.DuplicateResolutionBatchService;
import com.cadence.api.activities.DuplicateResolutionBatchService.ExplicitPair;
import com.cadence.api.activities.DuplicateResolutionBatchService.OverlapQueryFilters;
import com.cadence.api.activities.DuplicateResolutionBatchService.ResolveBatchResult;
import com.cadence.api.activities.DuplicateResolutionBatchService.UndoBatchResult;
import com.cadence.api.activities.Environment;
import com.cadence.api.activities.MergeCopyMode;
import com.cadence.api.activities.MergeRule;
import com.cadence.api.activities.MergeRuleService;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagMapper;
import com.cadence.api.activities.TagService;
import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.TagAttachResponse;
import com.cadence.api.activities.dto.TagResponse;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.WorkoutTagRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link ActivityService}/{@link TagService} the REST
 * {@code ActivityController}/{@code TagController} do. {@code update_activity} is deliberately
 * scoped to renaming only, not the REST PATCH endpoint's full field set (sport, linked workout/
 * gear, weight/hydration/temperature overrides) - those are structural edits the web app's own
 * UI already handles carefully; a virtual coach's legitimate need here is renaming and tagging,
 * matching what a real user asked their own coach to do that had no tool to act on.
 */
@Component
public class ActivityWriteTools {

	private final ActivityService activityService;
	private final DuplicateResolutionBatchService duplicateResolutionBatchService;
	private final ActivityMergeOrchestrator activityMergeOrchestrator;
	private final ActivityUnmergeService activityUnmergeService;
	private final MergeRuleService mergeRuleService;
	private final ActivitySourceService sourceService;
	private final TagService tagService;
	private final TagMapper tagMapper;
	private final ActivityTagRepository activityTagRepository;
	private final WorkoutTagRepository workoutTagRepository;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityWriteTools(ActivityService activityService,
			DuplicateResolutionBatchService duplicateResolutionBatchService,
			ActivityMergeOrchestrator activityMergeOrchestrator, ActivityUnmergeService activityUnmergeService,
			MergeRuleService mergeRuleService, ActivitySourceService sourceService, TagService tagService,
			TagMapper tagMapper, ActivityTagRepository activityTagRepository,
			WorkoutTagRepository workoutTagRepository, UserService userService, AccessGuard accessGuard,
			McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.duplicateResolutionBatchService = duplicateResolutionBatchService;
		this.activityMergeOrchestrator = activityMergeOrchestrator;
		this.activityUnmergeService = activityUnmergeService;
		this.mergeRuleService = mergeRuleService;
		this.sourceService = sourceService;
		this.tagService = tagService;
		this.tagMapper = tagMapper;
		this.activityTagRepository = activityTagRepository;
		this.workoutTagRepository = workoutTagRepository;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "update_activity", description = "Rename an activity (from list_activities/"
			+ "get_activity). Only the name can be changed through this tool - other fields "
			+ "(sport, linked workout/gear, weight/hydration/temperature overrides) require the "
			+ "web app.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ActivityResponse updateActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The new activity name", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		if (name == null || name.isBlank()) {
			throw new ValidationException("name must not be blank.", "name");
		}
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.updateActivity(activity, Map.of("name", name));
		return activityService.toResponse(updated);
	}

	@McpTool(name = "mark_duplicate", description = "Issue #498: marks activityId as a duplicate "
			+ "recording of primaryId (from list_overlapping_activities/list_activities) - "
			+ "activityId is hidden from TSS/CTL, threshold candidates and best efforts from now "
			+ "on, only primaryId counts. Both must belong to the same athlete, neither may "
			+ "already be linked some other way, and neither may be a multisport session or leg. "
			+ "Rebuilds the athlete's threshold history and re-rates affected activities' TSS/"
			+ "intensity before returning, since a duplicate-sourced best effort may no longer "
			+ "qualify as a threshold candidate.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ActivityResponse markDuplicate(
			@McpToolParam(description = "The activity to hide, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The activity it duplicates, which stays visible and keeps "
					+ "counting toward training load", required = true) String primaryId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.markDuplicate(activityId, primaryId);
		return activityService.toResponse(updated);
	}

	@McpTool(name = "unmark_duplicate", description = "Issue #498: reverses mark_duplicate - "
			+ "activityId counts toward TSS/CTL and threshold candidates again. A no-op (not an "
			+ "error) if it wasn't marked as a duplicate of anything. Rebuilds the athlete's "
			+ "threshold history and re-rates affected activities' TSS/intensity before returning.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ActivityResponse unmarkDuplicate(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.unmarkDuplicate(activityId);
		return activityService.toResponse(updated);
	}

	@McpTool(name = "set_primary", description = "Issue #498: swaps which copy of a duplicate "
			+ "pair (or larger group) is primary - activityId, currently hidden as a duplicate, "
			+ "becomes the visible primary, and the activity it used to duplicate becomes the "
			+ "duplicate instead. Fails if activityId is already the primary (nothing to swap). "
			+ "Rebuilds the athlete's threshold history and re-rates affected activities' TSS/"
			+ "intensity before returning.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ActivityResponse setPrimary(
			@McpToolParam(description = "The currently-hidden activity to promote, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.setPrimary(activityId);
		return activityService.toResponse(updated);
	}

	@McpTool(name = "merge_activities", description = "Issue #501: copies fields from sourceId into "
			+ "targetId - the target's timeline is authoritative, no stream rows are created or "
			+ "deleted. Resolves offsetSeconds automatically (cross-correlating cadence, falling "
			+ "back to power, over a window around the two activities' start_date delta) unless "
			+ "offsetSeconds overrides it. fields is any mix of stream fields (power, heartrate, "
			+ "cadence, altitude, lat, lng, speed, distance_km, air_temp, humidity, skin_temp, "
			+ "core_temp, heat_strain) and activity-level fields (name, avg_left_balance_pct, "
			+ "start_weight_kg, end_weight_kg, fluids_ml, bike_id, shoe_id). nulls controls what "
			+ "happens to a stream field where the source has no data for a second: "
			+ "'keep_original' (default) keeps the target's own value there; 'overwrite' nulls it "
			+ "out to mirror the source exactly. hideSource (default false) also marks sourceId as "
			+ "a hidden #498 duplicate of targetId. preview defaults to true here (the opposite of "
			+ "the web app's own default) - it computes and returns the exact result, including the "
			+ "resolved offset/alignment score and before/after summary, without writing anything; "
			+ "call again with preview=false once you're happy with it to actually apply the merge. "
			+ "copyMode (issue #503 phase 1) defaults to 'overwrite' (today's only prior behavior); "
			+ "'attach' is for power only - keeps target's existing power stream untouched and stores "
			+ "source's power stream alongside it as a non-primary source (see list_activity_sources/"
			+ "set_primary_source) instead of overwriting one with the other.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ActivityMergeService.MergeResult mergeActivities(
			@McpToolParam(description = "The activity to merge into, e.g. act_xxxxxxxxxxxx", required = true) String targetId,
			@McpToolParam(description = "The activity to copy fields from, e.g. act_xxxxxxxxxxxx "
					+ "(from list_overlapping_activities)", required = true) String sourceId,
			@McpToolParam(description = "Fields to copy - see the tool description for the full list",
					required = true) List<String> fields,
			@McpToolParam(description = "'keep_original' (default) or 'overwrite'", required = false) String nulls,
			@McpToolParam(description = "Also mark sourceId as a hidden #498 duplicate of targetId, default false",
					required = false) Boolean hideSource,
			@McpToolParam(description = "Overrides automatic time-alignment - positive means the source "
					+ "started this many seconds after the target. Omit to resolve one automatically",
					required = false) Integer offsetSeconds,
			@McpToolParam(description = "Compute and return the result without writing anything, default true "
					+ "(opposite of the REST/web default)", required = false) Boolean preview,
			@McpToolParam(description = "'overwrite' (default) or 'attach' (power only) - see the tool "
					+ "description", required = false) String copyMode) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity target = activityService.getActivity(targetId);
		accessGuard.requireWrite(target.getAthlete().getId());
		Activity source = activityService.getActivity(sourceId);
		accessGuard.requireWrite(source.getAthlete().getId());
		return activityMergeOrchestrator.merge(targetId, sourceId, fields, parseNullPolicy(nulls),
				offsetSeconds, Boolean.TRUE.equals(hideSource), preview == null || preview,
				parseCopyMode(copyMode));
	}

	@McpTool(name = "unmerge_activity", description = "Issue #501: reverses activityId's own latest "
			+ "active merge (from merge_activities) - restores its stream fields by re-parsing its "
			+ "own stored upload file, restores its activity-level fields from the snapshot the "
			+ "merge itself recorded, and un-hides the source if the merge had hidden it (unless "
			+ "something else has since re-resolved that source, which is left alone). Fails if "
			+ "there's no active merge, more than one is stacked on this activity (a merge chain - "
			+ "undo isn't supported for that), or the activity's own upload row has no stored file.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ActivityResponse unmergeActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		activityUnmergeService.undoMerge(activityId);
		return activityService.toResponse(activityService.getActivity(activityId));
	}

	@McpTool(name = "set_primary_source", description = "Issue #503 (phase 1): swaps activityId's "
			+ "current primary stream for field with the named sourceId's stream (from "
			+ "list_activity_sources) - e.g. switching a ride's power back to its Garmin/pedal "
			+ "reading after a Zwift copy became primary. The stream being replaced is itself kept "
			+ "as a new source (never lost), so this can always be reversed by calling it again with "
			+ "the other source's id. Reruns the same recompute cascade a merge triggers, so TSS/"
			+ "FTP-candidate data reflects the new primary immediately. Phase 1 only supports "
			+ "field='power'.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ActivityResponse setPrimarySource(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "'power' - phase 1 only supports power sources", required = true) String field,
			@McpToolParam(description = "The source id to promote, e.g. src_xxxxxxxxxxxx (from "
					+ "list_activity_sources)", required = true) String sourceId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		sourceService.setPrimarySource(activityId, field, sourceId);
		return activityService.toResponse(activityService.getActivity(activityId));
	}

	private NullPolicy parseNullPolicy(String nulls) {
		if (nulls == null || nulls.isBlank()) {
			return NullPolicy.KEEP_ORIGINAL;
		}
		try {
			return NullPolicy.fromWireValue(nulls.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("nulls must be 'keep_original' or 'overwrite'.", "nulls");
		}
	}

	private MergeCopyMode parseCopyMode(String copyMode) {
		if (copyMode == null || copyMode.isBlank()) {
			return MergeCopyMode.OVERWRITE;
		}
		try {
			return MergeCopyMode.fromWireValue(copyMode.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("copy_mode must be 'overwrite' or 'attach'.", "copy_mode");
		}
	}

	@McpTool(name = "set_merge_rule", description = "Issue #501 (phase 4): saves sport's automatic-"
			+ "merge rule - consulted whenever an overlapping pair of this sport is resolved with "
			+ "on_overlap=merge (import-time) or merge_activities' automatic path (not the manual "
			+ "merge_activities call, which always takes its own explicit fields/nulls). "
			+ "target_pattern/source_pattern are 'device:<prefix>' strings (e.g. 'device:zwift'), "
			+ "matched case-insensitively as a prefix against each candidate's device - whichever "
			+ "activity in a pair matches target_pattern becomes the merge target, the one matching "
			+ "source_pattern becomes the source; a pair where neither orientation matches is left "
			+ "flagged rather than guessed. Overwrites any existing rule for this sport.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> setMergeRule(
			@McpToolParam(description = "bike, run, swim, walk, or row", required = true) String sport,
			@McpToolParam(description = "'device:<prefix>', e.g. 'device:zwift'", required = true) String targetPattern,
			@McpToolParam(description = "'device:<prefix>', e.g. 'device:garmin'", required = true) String sourcePattern,
			@McpToolParam(description = "Fields to copy - same list as merge_activities", required = true) List<String> fields,
			@McpToolParam(description = "'keep_original' (default) or 'overwrite'", required = false) String nulls) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		MergeRule saved = mergeRuleService.setRule(athleteId, parseMergeRuleSport(sport), targetPattern, sourcePattern,
				fields, parseNullPolicy(nulls));
		MergeRuleService.ResolvedRule resolved = new MergeRuleService.ResolvedRule(saved.getTargetPattern(),
				saved.getSourcePattern(), saved.getFields(), saved.getNullPolicy());
		return mergeRuleResponse(saved.getSport(), resolved);
	}

	static Map<String, Object> mergeRuleResponse(Sport sport, MergeRuleService.ResolvedRule rule) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("sport", sport.name().toLowerCase());
		entry.put("target_pattern", rule.targetPattern());
		entry.put("source_pattern", rule.sourcePattern());
		entry.put("fields", rule.fields());
		entry.put("null_policy", rule.nullPolicy().wireValue());
		return entry;
	}

	private Sport parseMergeRuleSport(String sport) {
		try {
			Sport parsed = Sport.valueOf(sport.trim().toUpperCase());
			if (parsed == Sport.MULTISPORT || parsed == Sport.TRANSITION) {
				throw new IllegalArgumentException();
			}
			return parsed;
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new ValidationException("sport must be one of: bike, run, swim, walk, row.", "sport");
		}
	}

	@McpTool(name = "resolve_duplicates_batch", description = "Issue #498: applies action ('mark' "
			+ "or 'merge') to many overlap pairs at once - either an explicit "
			+ "pairs list of [activityId, otherActivityId], or every unresolved pair the overlap "
			+ "query's own filters find (same parameters as list_overlapping_activities: activityId "
			+ "pins an anchor, sport/device/otherDevice/environment/from/to/minDurationSeconds/"
			+ "otherHas/minOverlapPct/maxStartDeltaSeconds narrow it). Pass exactly one of pairs or "
			+ "the filter params. At most 100 pairs per call either way.\n\n"
			+ "dryRun (default true) only reports what would happen for each pair - which side "
			+ "would become primary, or that it would just be flagged - without writing anything. "
			+ "dryRun=false actually resolves every pair, recomputes the athlete's threshold "
			+ "history and TSS once at the end (not once per pair), and returns a batchId you can "
			+ "pass to undo_batch to reverse the whole thing later.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public Map<String, Object> resolveDuplicatesBatch(
			@McpToolParam(description = "mark or merge", required = true) String action,
			@McpToolParam(description = "Explicit [activityId, otherActivityId] pairs - pass this "
					+ "or the filter params below, not both", required = false) List<List<String>> pairs,
			@McpToolParam(description = "Pin a specific activity as anchor", required = false) String activityId,
			@McpToolParam(description = "Filter to one sport", required = false) String sport,
			@McpToolParam(description = "When true, sport only constrains the anchor side, not other",
					required = false) Boolean crossSport,
			@McpToolParam(description = "Case-insensitive prefix match on the anchor's device",
					required = false) String device,
			@McpToolParam(description = "Case-insensitive prefix match on the other side's device",
					required = false) String otherDevice,
			@McpToolParam(description = "outdoor or indoor, applied to both sides", required = false) String environment,
			@McpToolParam(description = "Inclusive lower bound on the anchor's start date (YYYY-MM-DD)",
					required = false) LocalDate from,
			@McpToolParam(description = "Inclusive upper bound on the anchor's start date (YYYY-MM-DD)",
					required = false) LocalDate to,
			@McpToolParam(description = "Ignore activities shorter than this on either side", required = false) Integer minDurationSeconds,
			@McpToolParam(description = "Repeatable: the other side must have all of these fields "
					+ "(power, heartrate, cadence, core_temp)", required = false) List<String> otherHas,
			@McpToolParam(description = "Minimum overlap as a % of the shorter activity's duration",
					required = false) Double minOverlapPct,
			@McpToolParam(description = "Only pairs whose start times are within this many seconds of each other",
					required = false) Long maxStartDeltaSeconds,
			@McpToolParam(description = "Max pairs to resolve from the query, capped at 100", required = false) Integer limit,
			@McpToolParam(description = "Preview only, default true", required = false) Boolean dryRun) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String callerSub = accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);
		User setBy = callerSub.equals(athleteId) ? athlete : userService.getById(callerSub);

		OverlapHandling parsedAction = parseAction(action);
		List<ExplicitPair> explicitPairs = null;
		if (pairs != null) {
			explicitPairs = new ArrayList<>();
			for (List<String> pair : pairs) {
				if (pair.size() != 2) {
					throw new ValidationException("Each pair must have exactly 2 activity ids.", "pairs");
				}
				explicitPairs.add(new ExplicitPair(pair.get(0), pair.get(1)));
			}
		}
		OverlapQueryFilters filters = new OverlapQueryFilters(activityId, parseSport(sport),
				Boolean.TRUE.equals(crossSport), device, otherDevice, parseEnvironment(environment), from, to,
				minDurationSeconds, otherHas, minOverlapPct, maxStartDeltaSeconds, limit);

		ResolveBatchResult result = duplicateResolutionBatchService.resolveDuplicatesBatch(
				athlete, parsedAction, explicitPairs, filters, dryRun == null || dryRun, setBy);
		return toResponseMap(result);
	}

	@McpTool(name = "undo_batch", description = "Issue #498: reverses a resolve_duplicates_batch"
			+ "(..., dryRun=false) call - unmarks every pair it actually marked (a flagged pair "
			+ "never changed anything, so there's nothing to reverse for it), restoring their gear "
			+ "mileage and recomputing the athlete's threshold history and TSS once at the end. A "
			+ "pair re-resolved or hand-edited since the batch applied is skipped rather than "
			+ "clobbered - see skippedCount in the result. Fails if this batch was already undone.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public Map<String, Object> undoBatch(
			@McpToolParam(description = "The batch id returned by resolve_duplicates_batch, e.g. drb_xxxxxxxxxxxx",
					required = true) String batchId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		UndoBatchResult result = duplicateResolutionBatchService.undoBatch(athlete, batchId);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("batch_id", result.batchId());
		response.put("reverted_count", result.revertedCount());
		response.put("skipped_count", result.skippedCount());
		return response;
	}

	private Map<String, Object> toResponseMap(ResolveBatchResult result) {
		List<Map<String, Object>> results = new ArrayList<>();
		for (var pairResult : result.results()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("activity_id", pairResult.activityId());
			entry.put("other_activity_id", pairResult.otherActivityId());
			entry.put("action", pairResult.action());
			entry.put("primary_activity_id", pairResult.primaryActivityId());
			results.add(entry);
		}
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("dry_run", result.dryRun());
		response.put("batch_id", result.batchId());
		response.put("results", results);
		return response;
	}

	private OverlapHandling parseAction(String action) {
		OverlapHandling parsed;
		try {
			parsed = OverlapHandling.fromWireValue(action);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new ValidationException("action must be 'mark' or 'merge'.", "action");
		}
		if (parsed != OverlapHandling.MARK && parsed != OverlapHandling.MERGE) {
			throw new ValidationException("action must be 'mark' or 'merge'.", "action");
		}
		return parsed;
	}

	private Sport parseSport(String sport) {
		if (sport == null || sport.isBlank()) {
			return null;
		}
		try {
			return Sport.valueOf(sport.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("sport must be one of: bike, run, swim, walk, row, multisport, transition.", "sport");
		}
	}

	private Environment parseEnvironment(String environment) {
		if (environment == null || environment.isBlank()) {
			return null;
		}
		try {
			return Environment.fromWireValue(environment.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("environment must be one of: outdoor, indoor.", "environment");
		}
	}

	@McpTool(name = "tag_activity", description = "Attach a tag to an activity (from "
			+ "list_activities/get_activity) by name - creates the tag if the athlete doesn't "
			+ "already have one with this name (case-insensitive), matching the web app's tag "
			+ "picker. A no-op if the activity already carries this tag. The returned tag's count "
			+ "is not populated here - use list_tags for usage counts.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public TagAttachResponse tagActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The tag name, e.g. \"VO2 max\"", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		if (name == null || name.isBlank()) {
			throw new ValidationException("name must not be blank.", "name");
		}
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		User athlete = userService.getById(activity.getAthlete().getId());
		Tag tag = tagService.attachTag(activity, athlete, null, name);
		return new TagAttachResponse(activityId, tagMapper.toResponse(tag));
	}

	@McpTool(name = "rename_tag", description = "Renames a tag (see list_tags for exact names) "
			+ "to new_name. If the athlete already has a different tag with that name "
			+ "(case-insensitive), merges into it instead - every activity or workout carrying "
			+ "the old tag ends up carrying the existing one, and the old tag is removed. Returns "
			+ "the tag that now holds new_name.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public TagResponse renameTag(
			@McpToolParam(description = "The tag's current name, e.g. \"VO2 max\"", required = true) String name,
			@McpToolParam(description = "The new name", required = true) String newName) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		Tag tag = tagService.findByName(athleteId, name);
		Tag result = tagService.renameTag(athleteId, tag.getId(), newName);
		long combinedCount = activityTagRepository.countByTagId(result.getId())
				+ workoutTagRepository.countByTagId(result.getId());
		return new TagResponse(result.getId(), result.getName(), result.getOrigin(), result.getColor(), combinedCount);
	}

	@McpTool(name = "delete_tag", description = "Deletes a tag (see list_tags for exact names) "
			+ "entirely, removing it from every activity and workout that carries it - not just "
			+ "an unused one.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
	public Map<String, Object> deleteTag(
			@McpToolParam(description = "The tag's name, e.g. \"VO2 max\"", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		Tag tag = tagService.findByName(athleteId, name);
		String deletedName = tag.getName();
		tagService.deleteTag(athleteId, tag.getId());
		return Map.of("deleted", true, "name", deletedName);
	}
}
