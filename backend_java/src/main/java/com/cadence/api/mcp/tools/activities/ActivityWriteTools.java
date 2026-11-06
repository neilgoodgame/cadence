package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.DuplicateResolutionBatchService;
import com.cadence.api.activities.DuplicateResolutionBatchService.ExplicitPair;
import com.cadence.api.activities.DuplicateResolutionBatchService.OverlapQueryFilters;
import com.cadence.api.activities.DuplicateResolutionBatchService.ResolveBatchResult;
import com.cadence.api.activities.DuplicateResolutionBatchService.UndoBatchResult;
import com.cadence.api.activities.Environment;
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
	private final TagService tagService;
	private final TagMapper tagMapper;
	private final ActivityTagRepository activityTagRepository;
	private final WorkoutTagRepository workoutTagRepository;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityWriteTools(ActivityService activityService,
			DuplicateResolutionBatchService duplicateResolutionBatchService, TagService tagService, TagMapper tagMapper,
			ActivityTagRepository activityTagRepository, WorkoutTagRepository workoutTagRepository,
			UserService userService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.duplicateResolutionBatchService = duplicateResolutionBatchService;
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

	@McpTool(name = "resolve_duplicates_batch", description = "Issue #498: applies action ('mark' "
			+ "or 'merge' - merge currently falls back to flag everywhere, same as every other "
			+ "overlap path, until #501 ships) to many overlap pairs at once - either an explicit "
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
