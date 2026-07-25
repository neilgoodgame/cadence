package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.BestEffortWindows;
import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import com.cadence.api.users.User;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@StepScope
public class BestEffortTasklet implements Tasklet {

	private static final int TOP_N = 5;

	private final UploadJobContext context;
	private final ActivityRepository activityRepository;
	private final BestEffortRepository bestEffortRepository;

	public BestEffortTasklet(UploadJobContext context, ActivityRepository activityRepository, BestEffortRepository bestEffortRepository) {
		this.context = context;
		this.activityRepository = activityRepository;
		this.bestEffortRepository = bestEffortRepository;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			User athlete = activity.getAthlete();
			List<Integer> powerSeries = segment.parsed().samples().stream().map(ParsedActivity.Sample::power).toList();
			List<Integer> hrSeries = segment.parsed().samples().stream().map(ParsedActivity.Sample::heartrate).toList();
			boolean hasHr = hrSeries.stream().anyMatch(Objects::nonNull);

			if (activity.getSport() == Sport.BIKE) {
				if (athlete.getFtp() != null) {
					updatePowerBestEfforts(activity, athlete, BestEffortKind.CYCLING_POWER, powerSeries);
				}
				if (hasHr) {
					updateHrBestEfforts(activity, athlete, BestEffortKind.CYCLING_HR, hrSeries);
				}
			}
			else if (activity.getSport() == Sport.RUN) {
				if (athlete.getCriticalRunPower() != null && powerSeries.stream().anyMatch(Objects::nonNull)) {
					updatePowerBestEfforts(activity, athlete, BestEffortKind.RUNNING_POWER, powerSeries);
				}
				List<Double> distanceKmSeries =
						segment.parsed().samples().stream().map(ParsedActivity.Sample::distanceKm).toList();
				updatePaceBestEfforts(activity, athlete, distanceKmSeries);
				if (hasHr) {
					updateHrBestEfforts(activity, athlete, BestEffortKind.RUNNING_HR, hrSeries);
				}
			}
		}
		return RepeatStatus.FINISHED;
	}

	private void updateHrBestEfforts(Activity activity, User athlete, BestEffortKind kind, List<Integer> hrSeries) {
		for (BestEffortWindows.PowerWindow window : BestEffortWindows.HR_BEST_EFFORT_WINDOWS) {
			if (window.seconds() > hrSeries.size()) continue;
			Double bestAvg = DurationCurveCalculator.bestAverage(hrSeries, window.seconds());
			if (bestAvg == null) continue;
			upsertEffort(activity, athlete, kind, window.label(), round1(bestAvg), "bpm");
			trimToTop(athlete.getId(), kind, window.label(), false);
		}
	}

	private void updatePowerBestEfforts(Activity activity, User athlete, BestEffortKind kind, List<Integer> powerSeries) {
		for (BestEffortWindows.PowerWindow window : BestEffortWindows.POWER_BEST_EFFORT_WINDOWS) {
			if (window.seconds() > powerSeries.size()) continue;
			Double bestAvg = DurationCurveCalculator.bestAverage(powerSeries, window.seconds());
			if (bestAvg == null) continue;
			upsertEffort(activity, athlete, kind, window.label(), round1(bestAvg), "watts");
			trimToTop(athlete.getId(), kind, window.label(), false);
		}
	}

	private void updatePaceBestEfforts(Activity activity, User athlete, List<Double> distanceKmSeries) {
		for (BestEffortWindows.PaceDistance distance : BestEffortWindows.PACE_BEST_EFFORT_DISTANCES_KM) {
			Double paceSecPerKm = PaceBestEffortCalculator.bestPaceSecondsPerKm(distanceKmSeries, distance.km());
			if (paceSecPerKm == null) continue;
			upsertEffort(activity, athlete, BestEffortKind.RUNNING_PACE, distance.label(), round1(paceSecPerKm), "sec_per_km");
			trimToTop(athlete.getId(), BestEffortKind.RUNNING_PACE, distance.label(), true);
		}
	}

	/** Upsert the per-activity best for a (kind, window) pair. */
	private void upsertEffort(Activity activity, User athlete, BestEffortKind kind, String window, double value, String unit) {
		var existing = bestEffortRepository.findByAthleteIdAndKindAndWindowAndActivityId(
				athlete.getId(), kind, window, activity.getId());
		BestEffort effort = existing.orElseGet(BestEffort::new);
		effort.setAthlete(athlete);
		effort.setKind(kind);
		effort.setWindow(window);
		effort.setValue(value);
		effort.setUnit(unit);
		effort.setDate(activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
		effort.setActivity(activity);
		bestEffortRepository.save(effort);
	}

	/** Keep only the top-N efforts for a (athlete, kind, window), deleting the rest. */
	private void trimToTop(String athleteId, BestEffortKind kind, String window, boolean lowerIsBetter) {
		List<BestEffort> ranked = lowerIsBetter
				? bestEffortRepository.findByAthleteIdAndKindAndWindowOrderByValueAsc(athleteId, kind, window)
				: bestEffortRepository.findByAthleteIdAndKindAndWindowOrderByValueDesc(athleteId, kind, window);
		if (ranked.size() > TOP_N) {
			bestEffortRepository.deleteAll(ranked.subList(TOP_N, ranked.size()));
		}
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
