package com.cadence.api.activities;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.athletes.ThresholdSuggestionService;
import com.cadence.api.common.domain.Sport;
import java.time.LocalDate;
import java.time.ZoneOffset;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.workouts.WorkoutAutoMatchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #525: the one real implementation of "recompute everything downstream of a changed
 * Record stream" - derived stats, decoupling/durability, duration curve, best efforts, the
 * threshold ledger/TSS cascade, and workout matching - that both the ingest pipeline ({@code
 * UploadJobFactory}'s tasklet chain) and merge/unmerge run through, replacing the interim,
 * merge-only class (issue #501 added while ingest was deliberately left untouched - {@code
 * ActivityMergePostProcessingService}). Mirrors backend's
 * activities/activity_post_processing.py - keep the two in sync when changing either.
 */
@Service
public class ActivityPostProcessingService {

	private final ActivityRepository activityRepository;
	private final ActivityService activityService;
	private final DerivedStatsRecomputeService derivedStatsRecomputeService;
	private final DurationCurveRecomputeService durationCurveRecomputeService;
	private final BestEffortRecomputeService bestEffortRecomputeService;
	private final WorkoutAutoMatchService workoutAutoMatchService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final ThresholdSuggestionService thresholdSuggestionService;

	public ActivityPostProcessingService(ActivityRepository activityRepository, ActivityService activityService,
			DerivedStatsRecomputeService derivedStatsRecomputeService,
			DurationCurveRecomputeService durationCurveRecomputeService,
			BestEffortRecomputeService bestEffortRecomputeService, WorkoutAutoMatchService workoutAutoMatchService,
			ThresholdHistoryService thresholdHistoryService, ThresholdSuggestionService thresholdSuggestionService) {
		this.activityRepository = activityRepository;
		this.activityService = activityService;
		this.derivedStatsRecomputeService = derivedStatsRecomputeService;
		this.durationCurveRecomputeService = durationCurveRecomputeService;
		this.bestEffortRecomputeService = bestEffortRecomputeService;
		this.workoutAutoMatchService = workoutAutoMatchService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.thresholdSuggestionService = thresholdSuggestionService;
	}

	/** Avg/norm/max power, avg/max HR, cadence, elevation, calories, TRIMP, run air temp/
	 * humidity, intensity+tss, plus (bike/run only) decoupling/durability - all re-derived from
	 * the activity's own stored Record rows ({@link DerivedStatsRecomputeService}, which already
	 * triggers decoupling/durability internally for bike/run - see its own Javadoc).
	 * {@code avgLeftBalancePct} is the one field that can never be recovered this way - see that
	 * class's own Javadoc - so only a caller with a fresh parse (ingest) supplies it; every other
	 * caller passes {@code null} and the field is left untouched. */
	@Transactional
	public void recomputePowerHrAndDecoupling(String targetId, Double avgLeftBalancePct) {
		derivedStatsRecomputeService.recomputeForActivity(targetId, avgLeftBalancePct);
	}

	/** Skipped for a multisport parent/transition leg - no fair curve/best-effort across a
	 * mixed-sport stream, same exclusion ingest itself applies. */
	@Transactional
	public void recomputeDurationCurveAndBestEfforts(String targetId) {
		Activity activity = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		if (activity.getSport() != Sport.MULTISPORT && activity.getSport() != Sport.TRANSITION) {
			durationCurveRecomputeService.recomputeForActivity(targetId);
			bestEffortRecomputeService.recomputeForActivity(targetId);
		}
	}

	/** Ingest-only: checks whether this brand-new activity's own effort raises or lowers the
	 * athlete's current threshold window, so {@link #processNewActivity} can rate this same
	 * activity's own TSS/intensity against the (possibly just-updated) result - see {@link
	 * ThresholdHistoryService#recomputeForActivity}'s own Javadoc. Merge/backfill rely on the
	 * athlete-wide cascade in {@link #recomputeAfterMerge} instead: an *existing* activity's
	 * rewritten stream can retroactively change who held a threshold on any day since its own
	 * date, which this single-activity check doesn't attempt. */
	@Transactional
	public void recomputeThresholdForNewActivity(Activity activity) {
		thresholdHistoryService.recomputeForActivity(activity);
		// A new bike/run activity can change the threshold suggestions list even when it didn't
		// change the current threshold itself - e.g. it's now the "near match" that suppresses an
		// upcoming_drop warning. Invalidated unconditionally for bike/run, not just on an actual
		// threshold change.
		if (activity.getSport() == Sport.BIKE || activity.getSport() == Sport.RUN) {
			thresholdSuggestionService.invalidate(activity.getAthlete().getId());
		}
	}

	/** Call once, right after a freshly-parsed activity's Record/Lap rows have been persisted -
	 * the real ingest-time sequence {@code UploadJobFactory}'s tasklet chain used to run as six
	 * separate steps. The threshold check runs first, before anything that could depend on it -
	 * not just TSS/decoupling ({@link #recomputePowerHrAndDecoupling}, last, needs the
	 * freshly-set threshold for both), but also {@code BestEffortComputeService}'s own
	 * {@code RUNNING_POWER} kind, which only fires when {@code athlete.getCriticalRunPower()} is
	 * already non-null: an athlete's very first qualifying run must bootstrap that value via
	 * this same threshold check before its own best effort can be recorded, or it's silently
	 * skipped (see {@code RunningPowerSourceIngestionTest}). Called unconditionally, including
	 * for a multisport parent - see this class's own Javadoc for why no {@code
	 * isMultisportParent} branch is needed here. */
	@Transactional
	public void processNewActivity(String targetId, Double avgLeftBalancePct) {
		Activity activity = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		recomputeThresholdForNewActivity(activity);
		recomputeDurationCurveAndBestEfforts(targetId);
		workoutAutoMatchService.attemptMatch(targetId);
		recomputePowerHrAndDecoupling(targetId, avgLeftBalancePct);
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
		recomputePowerHrAndDecoupling(targetId, null);
		recomputeDurationCurveAndBestEfforts(targetId);
	}
}
