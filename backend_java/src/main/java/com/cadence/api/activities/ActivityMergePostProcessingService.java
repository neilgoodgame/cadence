package com.cadence.api.activities;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.common.domain.Sport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.workouts.WorkoutAutoMatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #501 (phase 1, part 3): re-derives everything downstream of a merge's rewritten Record
 * stream (see {@link ActivityMergeApplyService}) - derived stats, decoupling/durability,
 * duration curve, best efforts, the threshold ledger/TSS cascade, and workout matching.
 *
 * <p>Hand-composes the existing per-activity/per-athlete recompute services already used for
 * backfills and the issue #498 duplicate-mark cascade, rather than a genuine shared
 * ActivityPostProcessingService the real ingest pipeline ({@code UploadJobFactory}'s tasklet
 * chain) also runs through - that refactor is tracked separately (issue #525) since it touches
 * live, already-deployed ingest code. This class is the interim, lower-risk composition and
 * should be deleted once #525 lands. Mirrors backend's activities/merge_postprocess.py - keep
 * the two in sync when changing either.
 */
@Service
public class ActivityMergePostProcessingService {

	private final ActivityRepository activityRepository;
	private final ActivityService activityService;
	private final DerivedStatsRecomputeService derivedStatsRecomputeService;
	private final DurationCurveRecomputeService durationCurveRecomputeService;
	private final BestEffortRecomputeService bestEffortRecomputeService;
	private final WorkoutAutoMatchService workoutAutoMatchService;

	public ActivityMergePostProcessingService(ActivityRepository activityRepository, ActivityService activityService,
			DerivedStatsRecomputeService derivedStatsRecomputeService,
			DurationCurveRecomputeService durationCurveRecomputeService,
			BestEffortRecomputeService bestEffortRecomputeService, WorkoutAutoMatchService workoutAutoMatchService) {
		this.activityRepository = activityRepository;
		this.activityService = activityService;
		this.derivedStatsRecomputeService = derivedStatsRecomputeService;
		this.durationCurveRecomputeService = durationCurveRecomputeService;
		this.bestEffortRecomputeService = bestEffortRecomputeService;
		this.workoutAutoMatchService = workoutAutoMatchService;
	}

	/** Call after {@link ActivityMergeApplyService#applyMergeFields} has committed - never on a
	 * preview. {@code targetId} keeps its own id across a merge, so this always re-derives the
	 * target, never the (now-exhausted) source. */
	@Transactional
	public void recomputeAfterMerge(String targetId) {
		recomputeAfterMerge(targetId, java.util.EnumSet.allOf(ThresholdField.class));
	}

	/** Scoped variant: the athlete-wide cascade only rebuilds {@code thresholdFields} (and skips
	 * the TSS re-rate when that's empty). A merge only rewrites the target's own streams, so a
	 * caller that knows the sports involved (see {@link ActivityMergeService#runMerge}) can pass
	 * just the fields those sports can define instead of all three. */
	@Transactional
	public void recomputeAfterMerge(String targetId, java.util.Set<ThresholdField> thresholdFields) {
		recomputeActivityLevelOnly(targetId);

		Activity activity = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));

		// Issue #498's cascade: this activity's own data just changed underneath it, so the
		// threshold ledger and every activity's TSS/intensity need the same athlete-wide rebuild
		// a duplicate mark/unmark/set_primary already triggers. Issue #545: from this activity's
		// own date - the merge only rewrote its streams, so nothing earlier in the ledger can
		// have changed.
		LocalDate fromDate = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
		activityService.recomputeAfterDuplicateChange(activity.getAthlete(), thresholdFields, fromDate);

		workoutAutoMatchService.attemptMatch(targetId);
	}

	/** Issue #503 (phase 2): everything {@link #recomputeAfterMerge} does except the athlete-wide
	 * cascade and the workout-match attempt. A bulk caller re-designating many activities for one
	 * athlete (e.g. {@code RunningPowerSourceRecomputeService}) calls this per activity, then
	 * {@code ActivityService#recomputeAfterDuplicateChange} once at the end, instead of once per
	 * activity - the cascade is a full athlete-wide rebuild, so calling it per activity would be
	 * quadratic in the athlete's activity count. The workout-match attempt is skipped entirely by
	 * such a caller, not just deferred - re-designating a power source never changes an
	 * activity's duration/distance/structure, so there's nothing for it to re-match. */
	@Transactional
	public void recomputeActivityLevelOnly(String targetId) {
		Activity activity = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));

		// Avg/norm/max power, cadence, elevation, calories, TRIMP, run air temp/humidity, plus
		// TSS/intensity and (bike/run only) decoupling/durability - all from the now-rewritten
		// Record rows.
		derivedStatsRecomputeService.recomputeForActivity(targetId);

		// A multisport parent's stream mixes sports (no fair curve/best-effort), same exclusion
		// DurationCurveTasklet/findRecomputeCandidates already apply at ingest/backfill time.
		if (activity.getSport() != Sport.MULTISPORT && activity.getSport() != Sport.TRANSITION) {
			durationCurveRecomputeService.recomputeForActivity(targetId);
			bestEffortRecomputeService.recomputeForActivity(targetId);
		}
	}
}
