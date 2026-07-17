package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.activities.calc.TrainingEffectLabel;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.Zone;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Average/normalized power, average/max HR, intensity factor, and TSS - the same numbers {@code GET /v1/activities/{id}} reads back. */
@Component
@StepScope
public class ComputeDerivedStatsTasklet implements Tasklet {

	private final UploadJobContext context;
	private final ActivityRepository activityRepository;
	private final ZoneService zoneService;

	public ComputeDerivedStatsTasklet(UploadJobContext context, ActivityRepository activityRepository, ZoneService zoneService) {
		this.context = context;
		this.activityRepository = activityRepository;
		this.zoneService = zoneService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		// A multisport parent's TSS is the sum of its legs' (no single threshold applies across
		// sports), so the legs must be computed first; the parent is the first segment.
		Activity parent = null;
		int childTssSum = 0;
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			boolean isMultisportParent = activity.getSport() == Sport.MULTISPORT;
			applySeriesStats(activity, segment.parsed());
			if (isMultisportParent) {
				parent = activity;
			}
			else {
				int tss = computeTss(activity, segment.parsed());
				activity.setTss(tss);
				childTssSum += tss;
			}
			activityRepository.save(activity);
		}
		if (parent != null) {
			parent.setTss(childTssSum);
			activityRepository.save(parent);
		}
		return RepeatStatus.FINISHED;
	}

	private void applySeriesStats(Activity activity, ParsedActivity parsed) {
		User athlete = activity.getAthlete();
		List<Integer> powerSeries = parsed.samples().stream().map(ParsedActivity.Sample::power).toList();
		List<Integer> hrSeries = parsed.samples().stream().map(ParsedActivity.Sample::heartrate).toList();

		Double normPower = powerSeries.stream().anyMatch(Objects::nonNull) ? NormalizedPowerCalculator.compute(powerSeries) : null;
		Double avgPower = mean(powerSeries);
		Double avgHr = mean(hrSeries);
		Integer maxHr = max(hrSeries);

		activity.setAvgPower(avgPower != null ? (int) Math.round(avgPower) : null);
		activity.setNormPower(normPower != null ? (int) Math.round(normPower) : null);
		activity.setAvgHr(avgHr != null ? (int) Math.round(avgHr) : null);
		activity.setMaxHr(maxHr);

		if (normPower != null && normPower > 0 && activity.getSport() == Sport.BIKE && athlete.getFtp() != null) {
			activity.setIntensity(round3(normPower / athlete.getFtp()));
		}
		else if (normPower != null && normPower > 0 && activity.getSport() == Sport.RUN && athlete.getCriticalRunPower() != null) {
			activity.setIntensity(round3(normPower / athlete.getCriticalRunPower()));
		}

		// Stryd-derived, run only (matches the Python backend - a bike's ambient readings
		// aren't meaningful the same way, and PATCH lets athletes set these manually for
		// activities with no sensor data instead).
		if (activity.getSport() == Sport.RUN) {
			List<Double> airTempSeries = parsed.samples().stream().map(ParsedActivity.Sample::airTemp).toList();
			List<Integer> humiditySeries = parsed.samples().stream().map(ParsedActivity.Sample::humidity).toList();
			if (airTempSeries.stream().anyMatch(Objects::nonNull)) {
				Double avgAirTemp = meanDouble(airTempSeries);
				activity.setAvgAirTemp(avgAirTemp != null ? round1(avgAirTemp) : null);
			}
			if (humiditySeries.stream().anyMatch(Objects::nonNull)) {
				Double avgHumidity = mean(humiditySeries);
				activity.setAvgHumidity(avgHumidity != null ? (int) Math.round(avgHumidity) : null);
			}
		}

		Double aerobicTrainingEffect = parsed.aerobicTrainingEffect();
		Double anaerobicTrainingEffect = parsed.anaerobicTrainingEffect();
		if (aerobicTrainingEffect != null || anaerobicTrainingEffect != null) {
			activity.setAerobicTrainingEffect(aerobicTrainingEffect);
			activity.setAnaerobicTrainingEffect(anaerobicTrainingEffect);
			activity.setTrainingEffectLabel(TrainingEffectLabel.of(aerobicTrainingEffect));
		}
	}

	private int computeTss(Activity activity, ParsedActivity parsed) {
		User athlete = activity.getAthlete();
		Integer normPower = activity.getNormPower();
		List<Integer> hrSeries = parsed.samples().stream().map(ParsedActivity.Sample::heartrate).toList();

		Integer thresholdPower = switch (activity.getSport()) {
			case BIKE -> athlete.getFtp();
			case RUN -> athlete.getCriticalRunPower();
			default -> null;
		};
		Integer tss = TssCalculator.powerBased(normPower != null ? normPower.doubleValue() : null, thresholdPower,
				activity.getMovingTime());
		if (tss == null) {
			List<Zone> zones = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE).getZones();
			Double threshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
			Map<String, Integer> secondsPerZone = TssCalculator.secondsPerZone(hrSeries, zones, threshold);
			tss = TssCalculator.hrBased(secondsPerZone, zones);
		}
		return tss;
	}

	private Double mean(List<Integer> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private Double meanDouble(List<Double> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private Integer max(List<Integer> values) {
		OptionalInt max = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).max();
		return max.isPresent() ? max.getAsInt() : null;
	}

	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
