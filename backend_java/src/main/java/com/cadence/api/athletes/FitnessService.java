package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The daily CTL (fitness)/ATL (fatigue)/TSB (form) series behind the dashboard training-load
 * chart: an exponentially-weighted moving average of daily TSS. Walked day-by-day from the
 * athlete's first-ever activity (so the EWMA starts from a true 0/0 baseline rather than an
 * arbitrary seed) through {@code to}, returning only the days in [from, to].
 */
@Service
public class FitnessService {

	private static final int CTL_DAYS = 42;
	private static final int ATL_DAYS = 7;

	private final ActivityRepository activityRepository;
	private final UserRepository userRepository;
	private final DecayLoadService decayLoadService;

	public FitnessService(ActivityRepository activityRepository, UserRepository userRepository,
			DecayLoadService decayLoadService) {
		this.activityRepository = activityRepository;
		this.userRepository = userRepository;
		this.decayLoadService = decayLoadService;
	}

	public List<FitnessPoint> computeFitnessSeries(String athleteId, LocalDate from, LocalDate to) {
		return computeFitnessSeries(athleteId, from, to, null);
	}

	/** As {@link #computeFitnessSeries(String, LocalDate, LocalDate)}, but restricted to one
	 * sport's activities when {@code sport} is non-null (e.g. a bike-only CTL to compare against
	 * FTP, independent of running load) - every other caller's combined-sport series is
	 * unaffected. */
	public List<FitnessPoint> computeFitnessSeries(String athleteId, LocalDate from, LocalDate to, Sport sport) {
		return computeFitnessSeries(athleteId, from, to, sport, false);
	}

	/** As {@link #computeFitnessSeries(String, LocalDate, LocalDate, Sport)}, but
	 * {@code includeDuplicates} (issue #498) can count a same-ride-twice duplicate copy's TSS
	 * too - only the MCP get_fitness_trend tool's ad-hoc inspection exposes this; every other
	 * caller leaves it at the default. */
	public List<FitnessPoint> computeFitnessSeries(String athleteId, LocalDate from, LocalDate to, Sport sport,
			boolean includeDuplicates) {
		LocalDate start = earliestStartOrFrom(athleteId, from);
		if (start.isAfter(to)) {
			return List.of();
		}
		Map<LocalDate, Double> tssByDay = dailyTss(athleteId, start, to, sport, includeDuplicates);
		return ewmaSeries(tssByDay, start, from, to);
	}

	public FitnessPoint computeFitnessPoint(String athleteId, LocalDate asOf) {
		List<FitnessPoint> series = computeFitnessSeries(athleteId, asOf, asOf);
		return series.isEmpty() ? new FitnessPoint(asOf, 0, 0, 0) : series.get(0);
	}

	/** Issue #487: as {@link #computeFitnessSeries}, but CTL/ATL/TSB built from above-Z2 training
	 * load ({@link DecayLoadService#aboveZ2LoadForActivity}) instead of raw TSS - a bike-only
	 * series (there's no pace-based above-Z2 histogram for runs) for comparing against the
	 * regular, TSS-based trend when calibrating the threshold-decay load term. Same
	 * baseline-start rule as computeFitnessSeries: the athlete's first-ever activity of any
	 * sport, not just bike. */
	public List<FitnessPoint> computeAboveZ2FitnessSeries(String athleteId, LocalDate from, LocalDate to,
			boolean includeDuplicates) {
		LocalDate start = earliestStartOrFrom(athleteId, from);
		if (start.isAfter(to)) {
			return List.of();
		}
		User athlete = userRepository.findById(athleteId).orElseThrow();
		Map<LocalDate, Double> dailyLoads = dailyAboveZ2Load(athlete, start, to, includeDuplicates);
		return ewmaSeries(dailyLoads, start, from, to);
	}

	private LocalDate earliestStartOrFrom(String athleteId, LocalDate from) {
		return activityRepository.findEarliestStartDate(athleteId)
				.map(i -> i.atZone(ZoneOffset.UTC).toLocalDate())
				.map(earliest -> earliest.isBefore(from) ? earliest : from)
				.orElse(from);
	}

	/** The CTL (42-day EWMA)/ATL (7-day EWMA)/TSB (CTL-ATL) walk, generalized to any per-day load
	 * value (issue #487) - originally hardwired to {@code dailyTss}'s TSS, now a plain {@code
	 * Map<LocalDate, Double>} any caller can build. Walks day-by-day from {@code start} (so
	 * CTL/ATL start from a true 0/0 baseline rather than an arbitrary seed) through {@code to},
	 * returning only the days in [from, to]. */
	private List<FitnessPoint> ewmaSeries(Map<LocalDate, Double> dailyValues, LocalDate start, LocalDate from, LocalDate to) {
		double ctl = 0;
		double atl = 0;
		List<FitnessPoint> series = new ArrayList<>();
		LocalDate day = start;
		while (!day.isAfter(to)) {
			double value = dailyValues.getOrDefault(day, 0.0);
			ctl += (value - ctl) / CTL_DAYS;
			atl += (value - atl) / ATL_DAYS;
			if (!day.isBefore(from)) {
				series.add(new FitnessPoint(day, round1(ctl), round1(atl), round1(ctl - atl)));
			}
			day = day.plusDays(1);
		}
		return series;
	}

	private Map<LocalDate, Double> dailyTss(String athleteId, LocalDate start, LocalDate to, Sport sport, boolean includeDuplicates) {
		Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant endInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Object[]> rows = sport != null
				? activityRepository.findStartDatesAndTssInRangeForSport(athleteId, sport, startInstant, endInstant, includeDuplicates)
				: activityRepository.findStartDatesAndTssInRange(athleteId, startInstant, endInstant, includeDuplicates);
		Map<LocalDate, Double> result = new HashMap<>();
		for (Object[] row : rows) {
			Instant startDate = (Instant) row[0];
			Integer tss = (Integer) row[1];
			LocalDate day = startDate.atZone(ZoneOffset.UTC).toLocalDate();
			result.merge(day, (double) (tss != null ? tss : 0), Double::sum);
		}
		return result;
	}

	/** Issue #487: every bike activity's above-Z2 training load ({@link
	 * DecayLoadService#aboveZ2LoadForActivity}, field=FTP - the load term's own bike-only scope),
	 * summed per day. Same multisport-child/duplicate exclusion {@link #dailyTss} uses, scoped to
	 * {@code Sport.BIKE} since there's no pace-based above-Z2 histogram for runs. */
	private Map<LocalDate, Double> dailyAboveZ2Load(User athlete, LocalDate start, LocalDate to, boolean includeDuplicates) {
		Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant endInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities = activityRepository.findForSportRange(
				athlete.getId(), Sport.BIKE, startInstant, endInstant, includeDuplicates);
		Map<LocalDate, Double> result = new HashMap<>();
		for (Activity activity : activities) {
			Double load = decayLoadService.aboveZ2LoadForActivity(activity, athlete, ThresholdField.FTP, null);
			LocalDate day = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
			result.merge(day, load != null ? load : 0.0, Double::sum);
		}
		return result;
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
