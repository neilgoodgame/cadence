package com.cadence.api.activities;

import com.cadence.api.activities.calc.DecouplingQualificationCalculator;
import com.cadence.api.activities.calc.DurabilityCalculator;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aerobic decoupling (Pw:HR) and durability (best power once tired) - see Activity's
 * decoupling* fields and ActivityDurability's own Javadoc for the full contract. Mirrors the
 * Python backend's uploads/processing.py compute_decoupling/compute_durability_rows/
 * compute_decoupling_and_durability_for_activity exactly, split the same way: the pure
 * qualification/durability math lives in activities.calc (DecouplingQualificationCalculator,
 * DurabilityCalculator, DurationCurveCalculator), this service owns the DB-touching assembly
 * (threshold lookup, Activity field writes, ActivityDurability bulk replace).
 */
@Service
public class ActivityDecouplingService {

	private static final Set<Sport> DECOUPLING_SPORTS = Set.of(Sport.BIKE, Sport.RUN);
	private static final double HOT_AIR_TEMP_C = 25.0;
	private static final double HOT_CORE_TEMP_C = 38.0;
	// A long steady session drives core temp up from sustained effort alone, even on a cool day
	// - e.g. a 3-hour run can cross 38.0 C on a 16 C day with no heat stress involved. Skin temp
	// only rises when the body can't dissipate heat into the air (hot/humid conditions), so
	// requiring both together is what actually distinguishes "hot session" from "just a long one."
	private static final double HOT_SKIN_TEMP_C = 33.0;

	private final RecordRepository recordRepository;
	private final ActivityDurabilityRepository durabilityRepository;
	private final ZoneService zoneService;

	public ActivityDecouplingService(
			RecordRepository recordRepository, ActivityDurabilityRepository durabilityRepository, ZoneService zoneService) {
		this.recordRepository = recordRepository;
		this.durabilityRepository = durabilityRepository;
		this.zoneService = zoneService;
	}

	/** Re-reads {@code activity}'s own stored Record rows and recomputes+persists both
	 * decoupling and durability - shared by ingest and both recompute paths (single-activity
	 * and bulk). Idempotent: deletes and reinserts ActivityDurability rows every time. */
	@Transactional
	public void computeAndPersist(Activity activity, User athlete) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
		if (activity.getSport() == Sport.RUN && !activity.matchesRunningPowerPreference(athlete)) {
			powerSeries = java.util.Collections.nCopies(powerSeries.size(), null);
		}
		List<Integer> hrSeries = records.stream().map(Record::getHeartrate).toList();
		List<Integer> tSeries = records.stream().map(Record::getT).toList();
		List<Double> airTempSeries = records.stream().map(Record::getAirTemp).toList();
		List<Double> coreTempSeries = records.stream().map(Record::getCoreTemp).toList();
		List<Double> skinTempSeries = records.stream().map(Record::getSkinTemp).toList();

		applyDecoupling(activity, athlete, powerSeries, hrSeries, tSeries, airTempSeries, coreTempSeries, skinTempSeries);

		List<DurabilityCalculator.Row> rows =
				DurabilityCalculator.computeRows(activity.getSport(), powerSeries, tSeries);
		durabilityRepository.deleteByActivityId(activity.getId());
		// Without an explicit flush, Hibernate can defer these per-entity removes past the
		// saveAll(...) below within the same flush, racing the unique (activity_id, threshold,
		// window_s) constraint against the not-yet-deleted old rows on a recompute.
		durabilityRepository.flush();
		if (!rows.isEmpty()) {
			List<ActivityDurability> entities = new ArrayList<>(rows.size());
			for (DurabilityCalculator.Row row : rows) {
				ActivityDurability entity = new ActivityDurability();
				entity.setActivity(activity);
				entity.setAthlete(athlete);
				entity.setSport(activity.getSport());
				entity.setBasis(row.basis());
				entity.setThreshold(row.threshold());
				entity.setWindowS(row.windowS());
				entity.setPower(row.power());
				entity.setStartOffsetS(row.startOffsetS());
				entity.setActivityDate(activity.getStartDate().atZone(java.time.ZoneOffset.UTC).toLocalDate());
				entities.add(entity);
			}
			durabilityRepository.saveAll(entities);
		}
	}

	/** Mutates activity's decoupling* fields in place (caller saves) - split out from
	 * computeAndPersist so the pure-series-in-memory ingest path (which already has
	 * power/hr/t/airTemp/coreTemp series without a Record round trip) can call it directly. */
	public void applyDecoupling(Activity activity, User athlete, List<Integer> powerSeries, List<Integer> hrSeries,
			List<Integer> tSeries, List<Double> airTempSeries, List<Double> coreTempSeries, List<Double> skinTempSeries) {
		if (!DECOUPLING_SPORTS.contains(activity.getSport())) {
			resetDecoupling(activity, List.of("sport"));
			return;
		}
		if (powerSeries.stream().noneMatch(Objects::nonNull)) {
			resetDecoupling(activity, List.of("no_power"));
			return;
		}

		Integer start = DecouplingQualificationCalculator.steadyWindowStartIndex(tSeries);
		if (start == null) {
			resetDecoupling(activity, List.of("short"));
			activity.setSteadySeconds(0);
			return;
		}

		List<Integer> windowPower = powerSeries.subList(start, powerSeries.size());
		List<Integer> windowHr = hrSeries.subList(start, hrSeries.size());
		List<Integer> windowT = tSeries.subList(start, tSeries.size());
		List<Double> windowAir = airTempSeries.subList(start, airTempSeries.size());
		List<Double> windowCore = coreTempSeries.subList(start, coreTempSeries.size());
		List<Double> windowSkin = skinTempSeries.subList(start, skinTempSeries.size());

		ZoneType zoneType = activity.getSport() == Sport.BIKE ? ZoneType.BIKE_POWER : ZoneType.RUN_POWER;
		Double threshold = zoneService.referenceFor(athlete, zoneType, activity);
		double viLimit = activity.getSport() == Sport.BIKE ? athlete.getDecouplingViLimitBike() : athlete.getDecouplingViLimitRun();
		DecouplingQualificationCalculator.Result check = DecouplingQualificationCalculator.checkQualification(
				activity.getSport(), windowPower, windowHr, threshold, viLimit, athlete.getDecouplingIfLimit(),
				athlete.getDecouplingMinSteadyMinutes() * 60);

		Double avgTemp = meanDouble(windowAir);
		Double avgCore = meanDouble(windowCore);
		Double avgSkin = meanDouble(windowSkin);
		boolean hot = (avgTemp != null && avgTemp >= HOT_AIR_TEMP_C)
				|| (avgCore != null && avgCore >= HOT_CORE_TEMP_C && avgSkin != null && avgSkin >= HOT_SKIN_TEMP_C);

		activity.setSteadySeconds(check.steadySeconds());
		activity.setDecouplingQualified(check.qualified());
		activity.setDecouplingReasons(check.reasons());
		activity.setDecouplingVi(check.vi());
		activity.setDecouplingIf(check.ifValue());
		activity.setDecouplingAvgTemp(avgTemp != null ? round1(avgTemp) : null);
		activity.setDecouplingAvgCore(avgCore != null ? round1(avgCore) : null);
		activity.setDecouplingHot(hot);
		activity.setDecouplingHrCoveragePct((double) Math.round(check.hrCoverage() * 100));
		activity.setDecouplingPowerCoveragePct((double) Math.round(check.powerCoverage() * 100));

		if (!check.qualified()) {
			activity.setDecouplingPct(null);
			activity.setEfFirst(null);
			activity.setEfSecond(null);
			activity.setDecouplingHalves(List.of());
			return;
		}

		int mid = windowPower.size() / 2;
		List<Map<String, Object>> halves = new ArrayList<>(2);
		Double[] efs = new Double[2];
		int[] bounds = {0, mid, mid, windowPower.size()};
		for (int half = 0; half < 2; half++) {
			int from = bounds[half * 2];
			int to = bounds[half * 2 + 1];
			List<Integer> halfPower = windowPower.subList(from, to);
			List<Integer> halfHr = windowHr.subList(from, to);
			List<Integer> halfT = windowT.subList(from, to);
			Double halfAvgPower = mean(halfPower);
			Double halfAvgHr = mean(halfHr);
			Double ef = (halfAvgPower != null && halfAvgHr != null && halfAvgHr != 0)
					? round3(halfAvgPower / halfAvgHr) : null;
			efs[half] = ef;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("start_s", halfT.isEmpty() ? null : halfT.get(0) - windowT.get(0));
			entry.put("end_s", halfT.isEmpty() ? null : halfT.get(halfT.size() - 1) - windowT.get(0));
			entry.put("power", halfAvgPower != null ? (int) Math.round(halfAvgPower) : null);
			entry.put("hr", halfAvgHr != null ? (int) Math.round(halfAvgHr) : null);
			entry.put("ef", ef);
			halves.add(entry);
		}

		if (efs[0] == null || efs[1] == null || efs[0] == 0) {
			activity.setDecouplingQualified(false);
			activity.setDecouplingReasons(List.of(efs[0] == null || efs[1] == null ? "hr_coverage" : "variable"));
			activity.setDecouplingPct(null);
			activity.setEfFirst(null);
			activity.setEfSecond(null);
			activity.setDecouplingHalves(List.of());
			return;
		}

		double decouplingPct = round1((efs[0] - efs[1]) / efs[0] * 100);
		activity.setDecouplingPct(decouplingPct);
		activity.setEfFirst(efs[0]);
		activity.setEfSecond(efs[1]);
		activity.setDecouplingHalves(halves);
	}

	private void resetDecoupling(Activity activity, List<String> reasons) {
		activity.setDecouplingPct(null);
		activity.setEfFirst(null);
		activity.setEfSecond(null);
		activity.setSteadySeconds(null);
		activity.setDecouplingQualified(false);
		activity.setDecouplingReasons(reasons);
		activity.setDecouplingVi(null);
		activity.setDecouplingIf(null);
		activity.setDecouplingAvgTemp(null);
		activity.setDecouplingAvgCore(null);
		activity.setDecouplingHot(false);
		activity.setDecouplingHrCoveragePct(null);
		activity.setDecouplingPowerCoveragePct(null);
		activity.setDecouplingHalves(List.of());
	}

	private Double mean(List<Integer> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private Double meanDouble(List<Double> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}
}
