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
		Activity activity = activityRepository.findById(context.getActivityId())
				.orElseThrow(() -> new NotFoundException("No such activity."));
		User athlete = activity.getAthlete();
		List<Integer> powerSeries = context.getParsed().samples().stream().map(ParsedActivity.Sample::power).toList();

		if (activity.getSport() == Sport.BIKE && athlete.getFtp() != null) {
			updatePowerBestEfforts(activity, athlete, BestEffortKind.CYCLING_POWER, powerSeries);
		}
		else if (activity.getSport() == Sport.RUN) {
			if (athlete.getCriticalRunPower() != null && powerSeries.stream().anyMatch(Objects::nonNull)) {
				updatePowerBestEfforts(activity, athlete, BestEffortKind.RUNNING_POWER, powerSeries);
			}
			List<Double> distanceKmSeries =
					context.getParsed().samples().stream().map(ParsedActivity.Sample::distanceKm).toList();
			updatePaceBestEfforts(activity, athlete, distanceKmSeries);
		}
		return RepeatStatus.FINISHED;
	}

	private void updatePowerBestEfforts(Activity activity, User athlete, BestEffortKind kind, List<Integer> powerSeries) {
		for (BestEffortWindows.PowerWindow window : BestEffortWindows.POWER_BEST_EFFORT_WINDOWS) {
			if (window.seconds() > powerSeries.size()) {
				continue;
			}
			Double bestAvg = DurationCurveCalculator.bestAverage(powerSeries, window.seconds());
			if (bestAvg == null) {
				continue;
			}
			var existing = bestEffortRepository.findByAthleteIdAndKindAndWindow(athlete.getId(), kind, window.label());
			if (existing.isEmpty() || bestAvg > existing.get().getValue()) {
				BestEffort effort = existing.orElseGet(BestEffort::new);
				effort.setAthlete(athlete);
				effort.setKind(kind);
				effort.setWindow(window.label());
				effort.setValue(round1(bestAvg));
				effort.setUnit("watts");
				effort.setDate(activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
				effort.setActivity(activity);
				bestEffortRepository.save(effort);
			}
		}
	}

	private void updatePaceBestEfforts(Activity activity, User athlete, List<Double> distanceKmSeries) {
		for (BestEffortWindows.PaceDistance distance : BestEffortWindows.PACE_BEST_EFFORT_DISTANCES_KM) {
			Double paceSecPerKm = PaceBestEffortCalculator.bestPaceSecondsPerKm(distanceKmSeries, distance.km());
			if (paceSecPerKm == null) {
				continue;
			}
			var existing = bestEffortRepository.findByAthleteIdAndKindAndWindow(
					athlete.getId(), BestEffortKind.RUNNING_PACE, distance.label());
			if (existing.isEmpty() || paceSecPerKm < existing.get().getValue()) {
				BestEffort effort = existing.orElseGet(BestEffort::new);
				effort.setAthlete(athlete);
				effort.setKind(BestEffortKind.RUNNING_PACE);
				effort.setWindow(distance.label());
				effort.setValue(round1(paceSecPerKm));
				effort.setUnit("sec_per_km");
				effort.setDate(activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
				effort.setActivity(activity);
				bestEffortRepository.save(effort);
			}
		}
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
