package com.cadence.api.activities;

import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #503 (phase 2): the bulk side of running-power re-designation - finds every run whose
 * stored {@code powerSource} no longer matches the athlete's *current* {@code
 * runningPowerSource} preference, swaps each one (reusing {@link
 * ActivitySourceService#swapPrimaryStream}) if it has a stored alternate to swap to, then runs
 * the shared athlete-wide recompute cascade ({@link ActivityService#recomputeAfterDuplicateChange})
 * exactly once at the end - not once per swapped activity, which would be quadratic in the
 * athlete's activity count (see {@link ActivityMergePostProcessingService
 * #recomputeActivityLevelOnly}'s own Javadoc).
 *
 * <p>Each activity is read, swapped, and recomputed in its own short transaction via {@link
 * TransactionTemplate} - the same reason {@link DerivedStatsRecomputeService#recomputeForAthlete}
 * does, rather than one long-running transaction across the whole athlete: an athlete can have
 * thousands of runs, and holding one transaction open across all of them risks exhausting the
 * connection/heap (see that class's own Javadoc for the live incident this avoids).
 *
 * <p>Called from {@code AthleteController}'s SSE {@code recompute-running-power-source}
 * endpoint. Mirrors backend's athletes/tasks.py's recompute_running_power_source - keep the two
 * in sync when changing either.
 */
@Service
public class RunningPowerSourceRecomputeService {

	public record Result(int swapped, int skipped) {
	}

	private final ActivityRepository activityRepository;
	private final ActivityStreamRepository streamRepository;
	private final ActivitySourceService sourceService;
	private final ActivityMergePostProcessingService postProcessingService;
	private final ActivityService activityService;
	private final TransactionTemplate tx;

	public RunningPowerSourceRecomputeService(ActivityRepository activityRepository,
			ActivityStreamRepository streamRepository, ActivitySourceService sourceService,
			ActivityMergePostProcessingService postProcessingService, ActivityService activityService,
			PlatformTransactionManager tm) {
		this.activityRepository = activityRepository;
		this.streamRepository = streamRepository;
		this.sourceService = sourceService;
		this.postProcessingService = postProcessingService;
		this.activityService = activityService;
		this.tx = new TransactionTemplate(tm);
	}

	/** {@code onProgress} is called once per candidate activity considered (swapped or skipped),
	 * with (processed-so-far, total) - the same shape {@code DerivedStatsRecomputeService
	 * #recomputeForAthlete} already reports progress in, so the SSE endpoint can forward it
	 * identically. */
	public Result recomputeForAthlete(User athlete, BiConsumer<Integer, Integer> onProgress) {
		RunningPowerSource preference = athlete.getRunningPowerSource();
		String athleteId = athlete.getId();
		List<String> mismatchedIds = tx.execute(status -> activityRepository
				.findRunningPowerSourceMismatches(athleteId, preference).stream().map(Activity::getId).toList());
		if (mismatchedIds == null) {
			mismatchedIds = List.of();
		}

		int swapped = 0;
		int skipped = 0;
		for (int i = 0; i < mismatchedIds.size(); i++) {
			if (Boolean.TRUE.equals(swapOne(mismatchedIds.get(i), preference))) {
				swapped++;
			}
			else {
				skipped++;
			}
			onProgress.accept(i + 1, mismatchedIds.size());
		}

		if (swapped > 0) {
			tx.executeWithoutResult(status -> activityService.recomputeAfterDuplicateChange(athlete));
		}
		return new Result(swapped, skipped);
	}

	/** @return true if a stored alternate was found and swapped in; false if this run's file
	 * never had the other candidate at all (e.g. a native-only Garmin file, imported back when
	 * the athlete's preference happened to be "native" and never re-uploaded since) - nothing
	 * stored to re-designate to, not an error. */
	private Boolean swapOne(String activityId, RunningPowerSource preference) {
		return tx.execute(status -> {
			Optional<ActivityStream> replacement = streamRepository
					.findTopByActivity_IdAndFieldAndSource_SourceKindOrderBySource_CreatedAtDesc(
							activityId, "power", preference.wireValue());
			if (replacement.isEmpty()) {
				return false;
			}
			sourceService.swapPrimaryStream(activityId, "power", replacement.get().getSource().getId());
			postProcessingService.recomputeActivityLevelOnly(activityId);
			return true;
		});
	}
}
