package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Rolling-window threshold determination: FTP, critical running power, and threshold pace are
 * each derived as the best qualifying effort within a trailing window
 * (User.getThresholdWindowDays()), not a one-way ratchet - as an old best effort ages out of the
 * window, a lower value takes over automatically.
 *
 * <p>Pure/read-only - nothing here writes to the database. Callers (the ingest hook, the manual-
 * refresh endpoint, the bulk rebuild endpoint) are responsible for persisting the results.
 */
@Service
public class ThresholdHistoryCalculator {

	public record Candidate(String activityId, LocalDate date, double impliedValue) {
	}

	public record ThresholdHistoryEntry(ThresholdField field, double value, String activityId, LocalDate effectiveFrom) {
	}

	// Pace is seconds/km - a *lower* value is the improvement, the opposite of every other field.
	private static final Set<ThresholdField> LOWER_IS_BETTER = Set.of(ThresholdField.THRESHOLD_PACE);

	// Bike FTP is conventionally estimated as 95% of best 20-minute power (the "20-minute test").
	private static final double FTP_TEST_MULTIPLIER = 0.95;
	private static final int FTP_TEST_WINDOW_SECONDS = 1200;
	// Running threshold (both criticalRunPower and thresholdPace) is conventionally estimated
	// directly from a sustained ~1-hour effort - no multiplier, unlike bike's 20-minute test.
	private static final int RUNNING_THRESHOLD_WINDOW_SECONDS = 3600;

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final EntityManager entityManager;

	public ThresholdHistoryCalculator(ActivityRepository activityRepository, RecordRepository recordRepository,
			EntityManager entityManager) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.entityManager = entityManager;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	/** The value this one activity's own best effort implies for `field` - raw units (watts for
	 * ftp/criticalRunPower, seconds/km for thresholdPace), or null if the activity has no
	 * qualifying effort (e.g. too short for the field's window). */
	private Double impliedValue(Activity activity, ThresholdField field) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (records.isEmpty()) {
			return null;
		}
		List<Integer> powerSeries = records.stream().map(Record::getPower).toList();
		List<Integer> tSeries = records.stream().map(Record::getT).toList();
		List<Double> distanceKmSeries = records.stream().map(Record::getDistanceKm).toList();

		return switch (field) {
			case FTP -> {
				Double best20Min = DurationCurveCalculator.bestAverage(powerSeries, FTP_TEST_WINDOW_SECONDS);
				yield best20Min == null ? null : (double) Math.round(FTP_TEST_MULTIPLIER * best20Min);
			}
			case CRITICAL_RUN_POWER -> {
				Double best60Min = DurationCurveCalculator.bestAverage(powerSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
				yield best60Min == null ? null : (double) Math.round(best60Min);
			}
			case THRESHOLD_PACE -> PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(
					tSeries, distanceKmSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
		};
	}

	/** Whether candidate value `a` beats existing value `b` for this field. */
	private static boolean isBetter(ThresholdField field, double a, double b) {
		return LOWER_IS_BETTER.contains(field) ? a < b : a > b;
	}

	/** False if candidateValue is an implausible outlier relative to referenceValue (e.g. corrupt
	 * power-meter data) - always true when there's no reference yet, since a first-ever value has
	 * nothing to sanity-check against. */
	private static boolean withinSanityBand(double candidateValue, Double referenceValue, int sanityPct) {
		if (referenceValue == null || referenceValue == 0) {
			return true;
		}
		double deviation = Math.abs(candidateValue - referenceValue) / referenceValue;
		return deviation <= sanityPct / 100.0;
	}

	// A local copy, not a cross-package import - matches this codebase's existing convention of
	// small local duplicates over reaching into another class's implementation detail (see e.g.
	// ThresholdDetectionService's own mmssToSeconds).
	private static Double mmssToSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(":");
		if (parts.length != 2) {
			return null;
		}
		try {
			int minutes = Integer.parseInt(parts[0]);
			int seconds = Integer.parseInt(parts[1]);
			return (double) (minutes * 60 + seconds);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static Double athleteReferenceValue(User athlete, ThresholdField field) {
		return switch (field) {
			case FTP -> athlete.getFtp() == null ? null : athlete.getFtp().doubleValue();
			case CRITICAL_RUN_POWER -> athlete.getCriticalRunPower() == null ? null : athlete.getCriticalRunPower().doubleValue();
			case THRESHOLD_PACE -> mmssToSeconds(athlete.getThresholdPace());
		};
	}

	private static LocalDate dateOf(Activity activity) {
		return activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
	}

	/** The best qualifying candidate among the athlete's activities in the trailing window ending
	 * asOf (default today) for `field` - cheap, bounded to ~windowDays worth of activities. Used
	 * by both the ingest hook and the manual-refresh endpoint. Sanity-filters each candidate
	 * against the athlete's current profile value (not a moving reference - this is a single
	 * snapshot-in-time scan, not a chronological replay; see replayFullHistory for that). */
	public Candidate currentWindowValue(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		LocalDate windowStart = effectiveAsOf.minusDays(athlete.getThresholdWindowDays());
		Double referenceValue = athleteReferenceValue(athlete, field);

		Instant start = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = effectiveAsOf.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end);

		Candidate best = null;
		for (Activity activity : activities) {
			Double implied = impliedValue(activity, field);
			if (implied != null && withinSanityBand(implied, referenceValue, athlete.getThresholdSanityPct())
					&& (best == null || isBetter(field, implied, best.impliedValue()))) {
				best = new Candidate(activity.getId(), dateOf(activity), implied);
			}
			// Load-bearing, not cosmetic (see ExportWriter's Javadoc for the same pattern):
			// Hibernate's persistence context pins every Activity and Record loaded here for the
			// life of the enclosing transaction. Bounded to ~windowDays worth of activities, but
			// a prolific athlete's trailing window can still be large enough to matter.
			entityManager.clear();
		}
		return best;
	}

	/** Rebuilds the full history ledger for one field by replaying the athlete's activities
	 * oldest-first, applying the same windowed-best-with-sanity-filter rule as
	 * currentWindowValue but incrementally over time - a date-windowed sliding-window-maximum
	 * problem (not count-windowed): `window` is a monotonic list of not-yet-expired, not-yet-
	 * dominated candidates, kept ordered so the front is always the current best (mirrors the
	 * classic sliding-window-maximum deque trick - candidates it dominates can never become the
	 * max again while it's still in the window, so they're safe to drop the moment a stronger,
	 * more recent one arrives).
	 *
	 * <p>Used only by the bulk "recompute history from oldest" tool - currentWindowValue is what
	 * ingest/refresh use day to day. */
	public List<ThresholdHistoryEntry> replayFullHistory(User athlete, ThresholdField field) {
		return replayFullHistory(athlete, field, (current, total) -> { });
	}

	/** As {@link #replayFullHistory(User, ThresholdField)}, but calls {@code onProgress(current,
	 * total)} after each activity is considered - what the bulk rebuild endpoint's SSE progress
	 * reads. */
	public List<ThresholdHistoryEntry> replayFullHistory(User athlete, ThresholdField field, BiConsumer<Integer, Integer> onProgress) {
		List<Activity> activities = activityRepository.findByAthleteIdAndSportOrderByStartDate(athlete.getId(), sportFor(field));

		List<ThresholdHistoryEntry> entries = new ArrayList<>();
		List<Candidate> window = new ArrayList<>();
		Double currentValue = null;
		int total = activities.size();
		int current = 0;

		for (Activity activity : activities) {
			LocalDate activityDate = dateOf(activity);
			LocalDate cutoff = activityDate.minusDays(athlete.getThresholdWindowDays());
			window = window.stream().filter(c -> c.date().isAfter(cutoff)).collect(Collectors.toCollection(ArrayList::new));

			Double implied = impliedValue(activity, field);
			if (implied != null && withinSanityBand(implied, currentValue, athlete.getThresholdSanityPct())) {
				Candidate candidate = new Candidate(activity.getId(), activityDate, implied);
				window = window.stream()
						.filter(c -> !isBetter(field, implied, c.impliedValue()))
						.collect(Collectors.toCollection(ArrayList::new));
				window.add(candidate);
			}

			Candidate best = window.isEmpty() ? null : window.get(0);
			Double newValue = best == null ? null : best.impliedValue();
			if (!Objects.equals(newValue, currentValue)) {
				currentValue = newValue;
				if (best != null) {
					entries.add(new ThresholdHistoryEntry(field, newValue, best.activityId(), best.date()));
				}
			}
			current++;
			onProgress.accept(current, total);
			// Load-bearing, not cosmetic (see ExportWriter's Javadoc for the same pattern):
			// Hibernate's persistence context pins every Activity and Record loaded here for the
			// life of the enclosing @Transactional call. Unlike currentWindowValue, this walks an
			// athlete's *entire* history - thousands of activities and millions of Record rows
			// for a long-established account - so without this the identity map grows unbounded
			// and OOMs the JVM well before the replay finishes (found live: crashed the backend
			// container on every attempt against a real account with 700+ activities per sport).
			entityManager.clear();
		}
		return entries;
	}
}
