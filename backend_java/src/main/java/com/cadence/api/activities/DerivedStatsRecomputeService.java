package com.cadence.api.activities;

import com.cadence.api.activities.calc.TrimpCalculator;
import com.cadence.api.athletes.Zone;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.UploadCalculations;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the extended Activity Analysis stats (max power, cadence, elevation, calories,
 * TRIMP) for activities ingested before that computation existed (or before an athlete had
 * the profile thresholds TRIMP needs), from stored per-second Record rows rather than
 * re-parsing the original upload file - same data source and reason TssRecomputeService
 * already uses.
 *
 * avgLeftBalancePct can NOT be backfilled this way: the FIT left_right_balance field is
 * deliberately never persisted per-sample onto Record (the UI only shows one aggregate
 * split, not a stream - see FitFileParser's leftBalancePct), so it's simply unrecoverable
 * for anything not re-ingested from the original file.
 */
@Service
public class DerivedStatsRecomputeService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final ZoneService zoneService;

	public DerivedStatsRecomputeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			ZoneService zoneService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.zoneService = zoneService;
	}

	@Transactional
	public Activity recomputeForActivity(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		User athlete = activity.getAthlete();
		List<Record> records = recordRepository.findByActivityIdOrderByT(activityId);

		List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
		Integer maxPower = max(powerSeries);
		if (maxPower != null) {
			activity.setMaxPower(maxPower);
		}

		List<Integer> cadenceSeries = records.stream().map(Record::getCadence).toList();
		if (cadenceSeries.stream().anyMatch(Objects::nonNull)) {
			Double avgCadence = mean(cadenceSeries);
			activity.setAvgCadence(avgCadence != null ? (int) Math.round(avgCadence) : null);
			activity.setMaxCadence(max(cadenceSeries));
		}

		List<Double> speedSeries = records.stream().map(Record::getSpeed).toList();
		Double maxSpeedMs = maxDouble(speedSeries);
		if (maxSpeedMs != null) {
			activity.setMaxSpeed(round1(maxSpeedMs * 3.6));
		}

		List<Double> altitudeSeries = records.stream().map(Record::getAltitude).toList();
		if (altitudeSeries.stream().anyMatch(Objects::nonNull)) {
			Double elevationMin = minDouble(altitudeSeries);
			Double elevationMax = maxDouble(altitudeSeries);
			activity.setElevationMin(elevationMin != null ? (int) Math.round(elevationMin) : null);
			activity.setElevationMax(elevationMax != null ? (int) Math.round(elevationMax) : null);
			activity.setAscent(UploadCalculations.totalAscentFromAltitudes(altitudeSeries));
			activity.setTotalDescent(UploadCalculations.totalDescentFromAltitudes(altitudeSeries));
		}

		Double avgPower = mean(powerSeries);
		if (avgPower != null) {
			double workKj = avgPower * activity.getMovingTime() / 1000.0;
			activity.setCalories(UploadCalculations.caloriesFromWorkKj(workKj));
		}

		List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
		List<Zone> hrZones = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE).getZones();
		Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
		Map<String, Integer> hrSecondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZones, hrThreshold);
		Double trimp = TrimpCalculator.compute(hrSecondsPerZone, hrZones);
		if (trimp != null) {
			activity.setTrimp(trimp);
		}

		return activityRepository.save(activity);
	}

	private Double mean(List<Integer> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).average();
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

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
