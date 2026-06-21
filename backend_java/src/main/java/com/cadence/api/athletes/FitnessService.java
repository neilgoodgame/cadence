package com.cadence.api.athletes;

import com.cadence.api.activities.ActivityRepository;
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

	public FitnessService(ActivityRepository activityRepository) {
		this.activityRepository = activityRepository;
	}

	public List<FitnessPoint> computeFitnessSeries(String athleteId, LocalDate from, LocalDate to) {
		LocalDate start = activityRepository.findEarliestStartDate(athleteId)
				.map(i -> i.atZone(ZoneOffset.UTC).toLocalDate())
				.map(earliest -> earliest.isBefore(from) ? earliest : from)
				.orElse(from);
		if (start.isAfter(to)) {
			return List.of();
		}

		Map<LocalDate, Integer> tssByDay = dailyTss(athleteId, start, to);

		double ctl = 0;
		double atl = 0;
		List<FitnessPoint> series = new ArrayList<>();
		LocalDate day = start;
		while (!day.isAfter(to)) {
			int tss = tssByDay.getOrDefault(day, 0);
			ctl += (tss - ctl) / CTL_DAYS;
			atl += (tss - atl) / ATL_DAYS;
			if (!day.isBefore(from)) {
				series.add(new FitnessPoint(day, round1(ctl), round1(atl), round1(ctl - atl)));
			}
			day = day.plusDays(1);
		}
		return series;
	}

	public FitnessPoint computeFitnessPoint(String athleteId, LocalDate asOf) {
		List<FitnessPoint> series = computeFitnessSeries(athleteId, asOf, asOf);
		return series.isEmpty() ? new FitnessPoint(asOf, 0, 0, 0) : series.get(0);
	}

	private Map<LocalDate, Integer> dailyTss(String athleteId, LocalDate start, LocalDate to) {
		Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant endInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Object[]> rows = activityRepository.findStartDatesAndTssInRange(athleteId, startInstant, endInstant);
		Map<LocalDate, Integer> result = new HashMap<>();
		for (Object[] row : rows) {
			Instant startDate = (Instant) row[0];
			Integer tss = (Integer) row[1];
			LocalDate day = startDate.atZone(ZoneOffset.UTC).toLocalDate();
			result.merge(day, tss != null ? tss : 0, Integer::sum);
		}
		return result;
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
