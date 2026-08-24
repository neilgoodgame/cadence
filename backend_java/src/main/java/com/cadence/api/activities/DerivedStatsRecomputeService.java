package com.cadence.api.activities;

import com.cadence.api.activities.calc.EnvironmentSanitizer;
import com.cadence.api.activities.calc.NormalizedPowerCalculator;
import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.activities.calc.TrimpCalculator;
import com.cadence.api.athletes.Zone;
import com.cadence.api.activities.calc.TssCalculator;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.UploadCalculations;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backfills the extended Activity Analysis stats (avg/norm/max power, cadence, elevation,
 * calories, TRIMP, run air temp/humidity) for activities ingested before that computation existed (or
 * before an athlete had the profile thresholds TRIMP needs, or - for air temp/humidity - before
 * EnvironmentSanitizer existed to correct a Stryd sensor-fallback reading), from stored
 * per-second Record rows rather than re-parsing the original upload file - same data source and
 * reason TssRecomputeService already uses.
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
	private final UserRepository userRepository;
	private final ZoneService zoneService;
	private final TransactionTemplate tx;

	public DerivedStatsRecomputeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			UserRepository userRepository, ZoneService zoneService, PlatformTransactionManager tm) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.userRepository = userRepository;
		this.zoneService = zoneService;
		this.tx = new TransactionTemplate(tm);
	}

	@Transactional
	public Activity recomputeForActivity(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		applyBackfill(activity, activity.getAthlete());
		return activityRepository.save(activity);
	}

	/** Bulk equivalent of {@link #recomputeForActivity} - backfills every one of the
	 * athlete's activities (same candidate set TssRecomputeService/BestEffortRecomputeService
	 * already use) and returns how many actually had data to backfill. Each activity is
	 * read, backfilled, and saved in its own transaction - same reason
	 * BestEffortRecomputeService#recomputeAll does, rather than holding one open across
	 * every activity: an athlete can have thousands of activities, each with its own
	 * per-second Record rows, and a single multi-minute transaction over all of them risked
	 * exhausting the connection/heap and crashing the app (seen live against 2,600+
	 * activities). Calls onProgress(current, total) after each activity. */
	public int recomputeForAthlete(User athlete, BiConsumer<Integer, Integer> onProgress) {
		String athleteId = athlete.getId();
		List<String> ids = tx.execute(status ->
				activityRepository.findRecomputeCandidates(athleteId).stream().map(Activity::getId).toList());
		if (ids == null) {
			return 0;
		}
		int updated = 0;
		for (int i = 0; i < ids.size(); i++) {
			if (Boolean.TRUE.equals(backfillAndSaveOne(ids.get(i), athleteId))) {
				updated++;
			}
			if (onProgress != null) {
				onProgress.accept(i + 1, ids.size());
			}
		}
		return updated;
	}

	private Boolean backfillAndSaveOne(String activityId, String athleteId) {
		return tx.execute(status -> {
			Activity activity = activityRepository.findById(activityId).orElse(null);
			if (activity == null) {
				return false;
			}
			User athlete = userRepository.findById(athleteId).orElse(null);
			if (athlete == null) {
				return false;
			}
			boolean changed = applyBackfill(activity, athlete);
			if (changed) {
				activityRepository.save(activity);
			}
			return changed;
		});
	}

	/** Mutates {@code activity} in place from its stored Record rows; returns whether
	 * anything was set (the caller decides whether/how to persist). */
	private boolean applyBackfill(Activity activity, User athlete) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		boolean changed = false;

		List<Integer> powerSeries = RunningPowerSanitizer.sanitize(
				records.stream().map(Record::getPower).toList(), activity.getSport(), athlete.getMaxRunningPowerWatts());
		Integer maxPower = max(powerSeries);
		if (maxPower != null) {
			activity.setMaxPower(maxPower);
			changed = true;
		}

		// avgPower/normPower: same sanitized series maxPower above uses - these predate
		// RunningPowerSanitizer being wired into ComputeDerivedStatsTasklet's ingest-time
		// computation for some historical activities, leaving a stale unsanitized value stuck
		// here (e.g. one Stryd spike sample dragging avgPower/normPower far above maxPower).
		Double avgPower = mean(powerSeries);
		if (avgPower != null) {
			activity.setAvgPower((int) Math.round(avgPower));
			changed = true;
		}
		Double normPower = null;
		if (powerSeries.stream().anyMatch(Objects::nonNull)) {
			normPower = NormalizedPowerCalculator.compute(powerSeries);
			if (normPower != null) {
				activity.setNormPower((int) Math.round(normPower));
				changed = true;
			}
		}

		// intensity: same normPower/threshold-power ratio ComputeDerivedStatsTasklet computes at
		// ingest time - like avgPower/normPower above, it's never touched once set, so a backfilled
		// normPower correction above would otherwise leave this stuck at its old, equally-skewed
		// value (e.g. a stale intensity of 11+ instead of the normal ~0.5-1.2 range).
		if (normPower != null && normPower > 0 && activity.getSport() == Sport.BIKE) {
			Double ftp = referenceForOrLive(athlete, ZoneType.BIKE_POWER, activity);
			if (ftp != null) {
				activity.setIntensity(round3(normPower / ftp));
				changed = true;
			}
		}
		else if (normPower != null && normPower > 0 && activity.getSport() == Sport.RUN) {
			Double criticalRunPower = referenceForOrLive(athlete, ZoneType.RUN_POWER, activity);
			if (criticalRunPower != null) {
				activity.setIntensity(round3(normPower / criticalRunPower));
				changed = true;
			}
		}

		List<Integer> cadenceSeries = records.stream().map(Record::getCadence).toList();
		if (cadenceSeries.stream().anyMatch(Objects::nonNull)) {
			Double avgCadence = mean(cadenceSeries);
			activity.setAvgCadence(avgCadence != null ? (int) Math.round(avgCadence) : null);
			activity.setMaxCadence(max(cadenceSeries));
			changed = true;
		}

		List<Double> speedSeries = records.stream().map(Record::getSpeed).toList();
		Double maxSpeedMs = maxDouble(speedSeries);
		if (maxSpeedMs != null) {
			activity.setMaxSpeed(round1(maxSpeedMs * 3.6));
			changed = true;
		}

		List<Double> altitudeSeries = records.stream().map(Record::getAltitude).toList();
		if (altitudeSeries.stream().anyMatch(Objects::nonNull)) {
			Double elevationMin = minDouble(altitudeSeries);
			Double elevationMax = maxDouble(altitudeSeries);
			activity.setElevationMin(elevationMin != null ? (int) Math.round(elevationMin) : null);
			activity.setElevationMax(elevationMax != null ? (int) Math.round(elevationMax) : null);
			activity.setAscent(UploadCalculations.totalAscentFromAltitudes(altitudeSeries));
			activity.setTotalDescent(UploadCalculations.totalDescentFromAltitudes(altitudeSeries));
			changed = true;
		}

		if (avgPower != null) {
			double workKj = avgPower * activity.getMovingTime() / 1000.0;
			activity.setCalories(UploadCalculations.caloriesFromWorkKj(workKj));
			changed = true;
		}

		// Stryd-derived, run only - same scope ComputeDerivedStatsTasklet's ingest-time
		// computation uses. Checked against the *raw* series (not sanitized) so a 0/0-fallback
		// activity - where sanitizing removes every sample - is recognized as "had data, now
		// corrected to null" rather than silently skipped and left showing its stale 0/0.
		if (activity.getSport() == Sport.RUN) {
			List<Double> airTempSeriesRaw = records.stream().map(Record::getAirTemp).toList();
			List<Integer> humiditySeriesRaw = records.stream().map(Record::getHumidity).toList();
			List<Integer> sanitizedHumidity = EnvironmentSanitizer.sanitizeHumidity(humiditySeriesRaw);
			List<Double> sanitizedAirTemp = EnvironmentSanitizer.sanitizeAirTemp(airTempSeriesRaw, humiditySeriesRaw);

			if (airTempSeriesRaw.stream().anyMatch(Objects::nonNull)) {
				Double avgAirTemp = meanDouble(sanitizedAirTemp);
				activity.setAvgAirTemp(avgAirTemp != null ? round1(avgAirTemp) : null);
				changed = true;
			}
			if (humiditySeriesRaw.stream().anyMatch(Objects::nonNull)) {
				Double avgHumidity = mean(sanitizedHumidity);
				activity.setAvgHumidity(avgHumidity != null ? (int) Math.round(avgHumidity) : null);
				changed = true;
			}
		}

		List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
		List<Zone> hrZones = zoneService.getOrCreate(athlete, ZoneType.HEART_RATE).getZones();
		Double hrThreshold = zoneService.referenceFor(athlete, ZoneType.HEART_RATE);
		Map<String, Integer> hrSecondsPerZone = TssCalculator.secondsPerZone(hrSeries, hrZones, hrThreshold);
		Double trimp = TrimpCalculator.compute(hrSecondsPerZone, hrZones);
		if (trimp != null) {
			activity.setTrimp(trimp);
			changed = true;
		}

		return changed;
	}

	/** Ledger entry effective as of {@code activity}'s own date, falling back to the athlete's
	 * live profile value when none is effective yet - mirrors ComputeDerivedStatsTasklet's
	 * ingest-time helper of the same name. */
	private Double referenceForOrLive(User athlete, ZoneType type, Activity activity) {
		Double value = zoneService.referenceFor(athlete, type, activity);
		return value != null ? value : zoneService.referenceFor(athlete, type);
	}

	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}

	private Double mean(List<Integer> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private Integer max(List<Integer> values) {
		OptionalInt max = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).max();
		return max.isPresent() ? max.getAsInt() : null;
	}

	private Double meanDouble(List<Double> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
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
