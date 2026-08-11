package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.activities.calc.TrainingEffectLabel;
import com.cadence.api.activities.calc.TrimpCalculator;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.Zone;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.UploadCalculations;
import com.cadence.api.uploads.parsing.ParsedActivity;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Average/normalized power, average/max HR, intensity factor, and TSS - the same numbers {@code GET /v1/activities/{id}} reads back. */
@Component
public class ComputeDerivedStatsTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final ZoneService zoneService;

	public ComputeDerivedStatsTasklet(UploadJobContextRegistry contextRegistry, ActivityRepository activityRepository,
			ZoneService zoneService) {
		this.contextRegistry = contextRegistry;
		this.activityRepository = activityRepository;
		this.zoneService = zoneService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
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

		if (normPower != null && normPower > 0 && activity.getSport() == Sport.BIKE && activity.getFtpSnapshot() != null) {
			activity.setIntensity(round3(normPower / activity.getFtpSnapshot()));
		}
		else if (normPower != null && normPower > 0 && activity.getSport() == Sport.RUN
				&& activity.getCriticalRunPowerSnapshot() != null) {
			activity.setIntensity(round3(normPower / activity.getCriticalRunPowerSnapshot()));
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

		Integer maxPower = max(powerSeries);
		if (maxPower != null) {
			activity.setMaxPower(maxPower);
		}

		List<Integer> cadenceSeries = parsed.samples().stream().map(ParsedActivity.Sample::cadence).toList();
		if (cadenceSeries.stream().anyMatch(Objects::nonNull)) {
			Double avgCadence = mean(cadenceSeries);
			activity.setAvgCadence(avgCadence != null ? (int) Math.round(avgCadence) : null);
			activity.setMaxCadence(max(cadenceSeries));
		}

		List<Double> speedSeries = parsed.samples().stream().map(ParsedActivity.Sample::speed).toList();
		Double maxSpeedMs = maxDouble(speedSeries);
		if (maxSpeedMs != null) {
			activity.setMaxSpeed(round1(maxSpeedMs * 3.6));
		}

		List<Double> altitudeSeries = parsed.samples().stream().map(ParsedActivity.Sample::altitude).toList();
		if (altitudeSeries.stream().anyMatch(Objects::nonNull)) {
			Double elevationMin = minDouble(altitudeSeries);
			Double elevationMax = maxDouble(altitudeSeries);
			activity.setElevationMin(elevationMin != null ? (int) Math.round(elevationMin) : null);
			activity.setElevationMax(elevationMax != null ? (int) Math.round(elevationMax) : null);
		}

		if (avgPower != null) {
			// Power-based estimate only - deliberately not falling back to an HR-based
			// estimate for power-less activities, which would be a much rougher guess.
			double workKj = avgPower * activity.getMovingTime() / 1000.0;
			activity.setCalories(UploadCalculations.caloriesFromWorkKj(workKj));
		}

		List<Zone> hrZones = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE).getZones();
		Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
		Map<String, Integer> hrSecondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZones, hrThreshold);
		Double trimp = TrimpCalculator.compute(hrSecondsPerZone, hrZones);
		if (trimp != null) {
			activity.setTrimp(trimp);
		}

		List<Double> leftBalanceSeries = parsed.samples().stream().map(ParsedActivity.Sample::leftBalancePct).toList();
		if (leftBalanceSeries.stream().anyMatch(Objects::nonNull)) {
			Double avgLeftBalance = meanDouble(leftBalanceSeries);
			activity.setAvgLeftBalancePct(avgLeftBalance != null ? round1(avgLeftBalance) : null);
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
			case BIKE -> activity.getFtpSnapshot();
			case RUN -> activity.getCriticalRunPowerSnapshot();
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

	private Double maxDouble(List<Double> values) {
		OptionalDouble max = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max();
		return max.isPresent() ? max.getAsDouble() : null;
	}

	private Double minDouble(List<Double> values) {
		OptionalDouble min = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).min();
		return min.isPresent() ? min.getAsDouble() : null;
	}

	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
