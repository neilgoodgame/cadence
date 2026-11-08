package com.cadence.api.activities;

import com.cadence.api.activities.MergeRuleService.ResolvedRule;
import com.cadence.api.activities.MergeRuleService.TargetSource;
import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.gear.GearMileageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #498: runs once per newly-ingested activity (see {@code ParseFileTasklet}) to detect and,
 * depending on the resolved {@link OverlapHandling} mode, resolve same-ride-twice duplicates
 * against the athlete's existing activities.
 *
 * <p>Detection itself always reuses {@link ActivityOverlapService} (the same query backing
 * {@code GET /v1/activities/overlaps}) pinned to the one activity just ingested, with the
 * endpoint's own defaults (50% minimum overlap, same sport on both sides, already-resolved pairs
 * excluded) - there's no separate ingest-time tolerance to keep in sync.
 *
 * <p>{@code MARK} picks a primary with {@link #choosePrimary}: the issue's own rule is "the Zwift
 * copy wins; otherwise prefer the athlete's power-source setting (#499), then more sensor
 * channels" - #499 doesn't exist yet, so this only implements the Zwift and sensor-channel steps,
 * with a deterministic (start date, then id) tie-break after that so the choice is never random.
 *
 * <p>{@code MERGE} (issue #501 phase 4): consults {@link MergeRuleService} for the athlete's
 * saved-or-built-in rule for the pair's sport. No rule, or neither activity matches either side
 * of the rule's device patterns, falls back to {@code FLAGGED} - there's nothing to safely
 * auto-decide. Otherwise previews the merge to get its alignment score; at or above {@link
 * #MERGE_ALIGNMENT_THRESHOLD} it applies for real ({@code MERGED}), hiding the source exactly
 * like a manual merge-and-hide would; below it, {@code MERGE_SKIPPED_LOW_SCORE} - detected and
 * reported, nothing written, same as {@code FLAG}. The threshold itself is a placeholder: issue
 * #501's own "Open questions" leaves "what correlation score allows automatic merging?" for
 * calibration against real pair data, which hasn't happened yet.
 *
 * <p>{@code FLAG} detects and reports but writes nothing; the pair is already visible as {@code
 * unresolved} through the overlap query itself, so there's no separate "flagged" state to
 * persist. */
@Service
public class ActivityOverlapDetectionService {

	/** Zwift + Garmin pairs are almost always exactly one real duplicate; this just bounds a
	 * pathological case (e.g. several short re-recorded attempts) from doing unbounded work. */
	private static final int MAX_PAIRS_PER_ACTIVITY = 10;

	/** See the class Javadoc's {@code MERGE} paragraph - not yet calibrated against real data. */
	static final double MERGE_ALIGNMENT_THRESHOLD = 0.9;

	private final ActivityOverlapService overlapService;
	private final ActivityRepository activityRepository;
	private final GearMileageService gearMileageService;
	private final MergeRuleService mergeRuleService;
	private final ActivityMergeOrchestrator mergeOrchestrator;

	public ActivityOverlapDetectionService(ActivityOverlapService overlapService, ActivityRepository activityRepository,
			GearMileageService gearMileageService, MergeRuleService mergeRuleService,
			ActivityMergeOrchestrator mergeOrchestrator) {
		this.overlapService = overlapService;
		this.activityRepository = activityRepository;
		this.gearMileageService = gearMileageService;
		this.mergeRuleService = mergeRuleService;
		this.mergeOrchestrator = mergeOrchestrator;
	}

	/** {@code mergeId} is set only when {@code action == MERGED} - {@code
	 * DuplicateResolutionBatchService#undoBatch}'s own check that the merge is still active
	 * before reversing it, the same re-resolved-since guard {@code primaryActivityId} already
	 * gives {@code MARKED}. */
	public record OverlapResolution(String otherActivityId, OverlapAction action, String primaryActivityId,
			String mergeId) {

		OverlapResolution(String otherActivityId, OverlapAction action, String primaryActivityId) {
			this(otherActivityId, action, primaryActivityId, null);
		}
	}

	@Transactional
	public List<OverlapResolution> detectAndResolve(Activity activity, OverlapHandling mode) {
		if (mode == OverlapHandling.OFF || isMultisportLinked(activity)) {
			return List.of();
		}

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				activity.getAthlete().getId(), activity.getId(), null, false, null, null, null, null, null, null,
				List.of(), null, null, false, null, MAX_PAIRS_PER_ACTIVITY);

		List<OverlapResolution> results = new ArrayList<>();
		for (OverlapPairResponse pair : page.data()) {
			if (!"unresolved".equals(pair.status())) {
				continue; // already duplicate/merged - nothing new to do
			}
			// activityId pins the anchor to `activity` itself, so the other side is always `other`.
			results.add(resolvePair(activity, pair.other().id(), mode));
		}
		return results;
	}

	/** Resolves one already-found unresolved pair - shared by {@link #detectAndResolve} (anchored
	 * to one newly-ingested activity) and the issue #498 backfill job ({@code
	 * OverlapBackfillRunner}/{@code --backfill-overlaps}), which instead walks every unresolved
	 * pair the overlap query returns for an athlete's whole history. */
	@Transactional
	public OverlapResolution resolvePair(String activityId, String otherActivityId, OverlapHandling mode) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new IllegalStateException("No such activity: " + activityId));
		return resolvePair(activity, otherActivityId, mode);
	}

	private OverlapResolution resolvePair(Activity activity, String otherId, OverlapHandling mode) {
		if (mode == OverlapHandling.MARK) {
			Activity primary = mark(activity, otherId);
			if (primary != null) {
				return new OverlapResolution(otherId, OverlapAction.MARKED, primary.getId());
			}
		}
		if (mode == OverlapHandling.MERGE) {
			OverlapResolution merged = merge(activity, otherId, true);
			if (merged != null) {
				return merged;
			}
		}
		// FLAG - also where MARK lands when the pair turned out not to be linkable (see
		// canLink), and where MERGE lands when no rule/pattern match applies (see merge).
		return new OverlapResolution(otherId, OverlapAction.FLAGGED, null);
	}

	/** @return {@code MERGED} (applied for real) or {@code MERGE_SKIPPED_LOW_SCORE} (previewed
	 * only - the alignment score didn't clear {@link #MERGE_ALIGNMENT_THRESHOLD}) if {@code
	 * mergeRuleService} found an applicable rule and a device-pattern match for this pair; null
	 * if neither applies, in which case the caller falls back to {@code FLAGGED}. {@code apply}
	 * false (the preview path) never writes anything regardless of score - it always previews. */
	private OverlapResolution merge(Activity activity, String otherId, boolean apply) {
		ResolvedRule rule = mergeRuleService.resolve(activity.getAthlete().getId(), activity.getSport());
		if (rule == null) {
			return null;
		}
		Activity other = activityRepository.findById(otherId).orElse(null);
		if (other == null) {
			return null;
		}
		TargetSource assignment = mergeRuleService.assign(activity, other, rule);
		if (assignment == null) {
			return null;
		}
		// Fast-fail on the one ActivityMergeService#validate rule worth pre-checking here (same
		// spirit as mark()'s canLink) - still wrapped in the broader try/catch below as a safety
		// net for any other validation rule, since this isn't meant to duplicate validate()'s
		// full rule set, just avoid a wasted preview computation for the common case.
		if (isMultisportLinked(assignment.target()) || isMultisportLinked(assignment.source())) {
			return null;
		}
		try {
			ActivityMergeService.MergeResult preview = mergeOrchestrator.merge(assignment.target().getId(),
					assignment.source().getId(), rule.fields(), rule.nullPolicy(), null, true, true);
			if (preview.alignmentScore() == null || preview.alignmentScore() < MERGE_ALIGNMENT_THRESHOLD) {
				return new OverlapResolution(otherId, OverlapAction.MERGE_SKIPPED_LOW_SCORE, null);
			}
			if (!apply) {
				return new OverlapResolution(otherId, OverlapAction.MERGED, assignment.target().getId());
			}
			ActivityMergeService.MergeResult result = mergeOrchestrator.merge(assignment.target().getId(),
					assignment.source().getId(), rule.fields(), rule.nullPolicy(), null, true, false);
			return new OverlapResolution(otherId, OverlapAction.MERGED, assignment.target().getId(), result.mergeId());
		}
		catch (ValidationException e) {
			// The rule's device patterns matched, but ActivityMergeService's own deeper checks
			// (e.g. one side already a duplicate of some third activity) refused it - same
			// "can't safely do this automatically, fall back to flagging" outcome as mark()'s
			// own canLink failure, just discovered one layer further in.
			return null;
		}
	}

	/** @return the primary if {@code activity} and {@code other} were linked (one of them saved
	 * as the other's duplicate); null if the pair couldn't be safely linked (see {@link
	 * #canLink}), in which case the caller reports it as merely flagged instead. */
	private Activity mark(Activity activity, String otherId) {
		Activity other = activityRepository.findById(otherId).orElse(null);
		if (other == null) {
			return null;
		}
		boolean activityIsPrimary = choosePrimary(activity, other) == activity;
		Activity primary = activityIsPrimary ? activity : other;
		Activity duplicate = activityIsPrimary ? other : activity;
		if (!canLink(duplicate, primary)) {
			return null;
		}
		duplicate.setPrimaryActivity(primary);
		Activity saved = activityRepository.save(duplicate);
		String bikeId = saved.getBike() == null ? null : saved.getBike().getId();
		String shoeId = saved.getShoe() == null ? null : saved.getShoe().getId();
		gearMileageService.applyDuplicateChange(bikeId, shoeId, saved.getDistanceKm(), false, true);
		return primary;
	}

	/** Non-mutating counterpart to {@link #resolvePair} - the exact same {@link #choosePrimary}/
	 * {@link #canLink} decision, without writing anything. Used by
	 * {@code DuplicateResolutionBatchService}'s dry-run mode. */
	public OverlapResolution previewResolution(String activityId, String otherId, OverlapHandling mode) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new IllegalStateException("No such activity: " + activityId));
		Activity other = activityRepository.findById(otherId)
				.orElseThrow(() -> new IllegalStateException("No such activity: " + otherId));
		if (mode == OverlapHandling.MARK) {
			boolean activityIsPrimary = choosePrimary(activity, other) == activity;
			Activity primary = activityIsPrimary ? activity : other;
			Activity duplicate = activityIsPrimary ? other : activity;
			if (canLink(duplicate, primary)) {
				return new OverlapResolution(otherId, OverlapAction.MARKED, primary.getId());
			}
		}
		if (mode == OverlapHandling.MERGE) {
			// apply=false - merge()'s own preview=true call already has zero side effects, so
			// this never needs to go further even when the score would qualify for real.
			OverlapResolution merged = merge(activity, otherId, false);
			if (merged != null) {
				return merged;
			}
		}
		return new OverlapResolution(otherId, OverlapAction.FLAGGED, null);
	}

	/** Mirrors {@code ActivityService.resolvePrimaryActivity}'s validation for the manual
	 * {@code PATCH .../primary_activity_id} path, now that {@link #choosePrimary} has settled
	 * which side would play which role - same athlete is already guaranteed by the overlap query
	 * itself. Deliberately asymmetric: {@code primary} having other duplicates already pointing
	 * to it is fine (the normal state for an established primary); only {@code duplicate} having
	 * duplicates of its own, or either side already being linked some other way, blocks it. */
	private boolean canLink(Activity duplicate, Activity primary) {
		if (duplicate.getPrimaryActivity() != null || primary.getPrimaryActivity() != null) {
			return false; // one side is already a duplicate of some third activity
		}
		if (isMultisportLinked(duplicate) || isMultisportLinked(primary)) {
			return false;
		}
		return !activityRepository.existsByPrimaryActivityId(duplicate.getId());
	}

	private boolean isMultisportLinked(Activity activity) {
		return activity.getSport() == Sport.MULTISPORT || activity.getSport() == Sport.TRANSITION
				|| activity.getParentActivity() != null;
	}

	/** @return whichever of the two should survive as the primary. Zwift wins outright (it
	 * carries the route, virtual GPS track, distance/speed and elevation - see issue #498); failing
	 * that, the side with more populated sensor summary fields (power/HR/cadence/core temp) wins;
	 * failing that, the earlier start date, then the lower id, so the result is always
	 * deterministic rather than depending on which copy happened to import first. */
	static Activity choosePrimary(Activity a, Activity b) {
		boolean aZwift = isZwift(a);
		boolean bZwift = isZwift(b);
		if (aZwift != bZwift) {
			return aZwift ? a : b;
		}
		int aSensors = sensorChannelCount(a);
		int bSensors = sensorChannelCount(b);
		if (aSensors != bSensors) {
			return aSensors > bSensors ? a : b;
		}
		if (!a.getStartDate().equals(b.getStartDate())) {
			return a.getStartDate().isBefore(b.getStartDate()) ? a : b;
		}
		return a.getId().compareTo(b.getId()) <= 0 ? a : b;
	}

	private static boolean isZwift(Activity activity) {
		return activity.getDevice() != null && activity.getDevice().toLowerCase(Locale.ROOT).startsWith("zwift");
	}

	private static int sensorChannelCount(Activity activity) {
		int count = 0;
		if (activity.getAvgPower() != null) count++;
		if (activity.getAvgHr() != null) count++;
		if (activity.getAvgCadence() != null) count++;
		if (activity.getAvgCoreTemp() != null) count++;
		return count;
	}
}
