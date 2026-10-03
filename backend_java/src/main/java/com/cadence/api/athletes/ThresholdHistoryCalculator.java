package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.users.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

	/** currentFrom is the date whose recompute pass discovered this as the new window winner -
	 * see {@link ThresholdHistory#getCurrentFrom()}'s Javadoc for why that can differ from
	 * effectiveFrom (the winning candidate's own activity date). */
	public record ThresholdHistoryEntry(
			ThresholdField field, double value, String activityId, LocalDate effectiveFrom, LocalDate currentFrom) {
	}

	/** Like {@link Candidate}, but also carries the window label and the raw pre-transform value
	 * (e.g. the raw 20-min power before FTP's x0.95 multiplier) - used by the Threshold
	 * suggestions feature's "implies" copy for a rejected candidate. */
	public record RejectedCandidate(String activityId, LocalDate date, double impliedValue, String window, double rawValue) {
	}

	/** Result of {@link #upcomingDrop} - shape varies with which of the two kinds applies. See
	 * that method's Javadoc for the suppression rules that decide between them (or neither, via
	 * a null return). */
	public sealed interface UpcomingDropResult {
		record UpcomingDrop(ThresholdHistory currentEntry, Candidate successor, LocalDate expiry, int daysLeft)
				implements UpcomingDropResult {
		}

		record RaceWillRefresh(Race race, LocalDate expiry, int daysLeft) implements UpcomingDropResult {
		}
	}

	// Pace is seconds/km - a *lower* value is the improvement, the opposite of every other field.
	private static final Set<ThresholdField> LOWER_IS_BETTER = Set.of(ThresholdField.THRESHOLD_PACE);

	// Bike FTP is conventionally estimated as 95% of best 20-minute power (the "20-minute test").
	private static final double FTP_TEST_MULTIPLIER = 0.95;
	private static final int FTP_TEST_WINDOW_SECONDS = 1200;
	// Running threshold (both criticalRunPower and thresholdPace) is conventionally estimated
	// directly from a sustained ~1-hour effort - no multiplier, unlike bike's 20-minute test.
	// Also reused for FTP under FtpCalculationMethod.SIXTY_MIN_DIRECT - same window, same "direct,
	// no multiplier" reasoning, just applied to bike power instead of running.
	private static final int RUNNING_THRESHOLD_WINDOW_SECONDS = 3600;

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final EntityManager entityManager;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final AcceptedThresholdCandidateRepository acceptedThresholdCandidateRepository;
	private final RaceRepository raceRepository;

	public ThresholdHistoryCalculator(ActivityRepository activityRepository, RecordRepository recordRepository,
			EntityManager entityManager, ThresholdHistoryRepository thresholdHistoryRepository,
			AcceptedThresholdCandidateRepository acceptedThresholdCandidateRepository, RaceRepository raceRepository) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.entityManager = entityManager;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.acceptedThresholdCandidateRepository = acceptedThresholdCandidateRepository;
		this.raceRepository = raceRepository;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	/** Like {@link #impliedValue}, but also returns the window label and the raw pre-transform
	 * value (e.g. the raw 20-min power before FTP's x0.95 multiplier) - used directly by the
	 * Threshold suggestions feature's "implies" copy for a rejected candidate; impliedValue below
	 * is a thin wrapper over this for every other, much more numerous caller that only ever
	 * wanted the final value. */
	private record ImpliedValueDetail(String window, double rawValue, double value) {
	}

	private ImpliedValueDetail impliedValueDetail(Activity activity, User athlete, ThresholdField field) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (records.isEmpty()) {
			return null;
		}
		List<Integer> powerSeries = RunningPowerSanitizer.sanitize(
				records.stream().map(Record::getPower).toList(), activity.getSport(), athlete.getMaxRunningPowerWatts());
		List<Integer> tSeries = records.stream().map(Record::getT).toList();
		List<Double> distanceKmSeries = records.stream().map(Record::getDistanceKm).toList();

		return switch (field) {
			case FTP -> {
				if (athlete.getFtpCalculationMethod() == FtpCalculationMethod.SIXTY_MIN_DIRECT) {
					Double best60Min = DurationCurveCalculator.bestAverage(powerSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
					yield best60Min == null ? null : new ImpliedValueDetail("60min", best60Min, Math.round(best60Min));
				}
				Double best20Min = DurationCurveCalculator.bestAverage(powerSeries, FTP_TEST_WINDOW_SECONDS);
				yield best20Min == null ? null
						: new ImpliedValueDetail("20min", best20Min, Math.round(FTP_TEST_MULTIPLIER * best20Min));
			}
			case CRITICAL_RUN_POWER -> {
				// Excluded (source doesn't match the athlete's current preference), or every
				// sample is genuinely absent (e.g. "native" preferred but this file has no
				// native power meter at all) - either way, not a qualifying candidate.
				// bestAverage treats a missing sample as 0W, not "no data," so an all-absent
				// series must be caught here rather than left to fall through to it.
				if (!activity.matchesRunningPowerPreference(athlete) || powerSeries.stream().noneMatch(Objects::nonNull)) {
					yield null;
				}
				Double best60Min = DurationCurveCalculator.bestAverage(powerSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
				yield best60Min == null ? null : new ImpliedValueDetail("60min", best60Min, Math.round(best60Min));
			}
			case THRESHOLD_PACE -> {
				Double bestPace = PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(
						tSeries, distanceKmSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
				// Rounded to whole seconds like FTP/CRITICAL_RUN_POWER above - valuePace is stored
				// (and re-parsed via mmssToSeconds for recomputeAndRecord's dead-row comparison) as
				// whole-second "M:SS", so a raw sub-second double here could never compare equal to
				// the already-rounded stored value: every re-ingest inserted a spurious duplicate
				// row for the same still-current pace instead of recognizing it as unchanged.
				yield bestPace == null ? null : new ImpliedValueDetail("60min", bestPace, Math.round(bestPace));
			}
		};
	}

	/** The value this one activity's own best effort implies for `field` - raw units (watts for
	 * ftp/criticalRunPower, seconds/km for thresholdPace), or null if the activity has no
	 * qualifying effort (e.g. too short for the field's window). */
	private Double impliedValue(Activity activity, User athlete, ThresholdField field) {
		ImpliedValueDetail detail = impliedValueDetail(activity, athlete, field);
		return detail == null ? null : detail.value();
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

	/** Activity ids the athlete has explicitly accepted as real despite failing the sanity band -
	 * see the Threshold suggestions feature's AcceptedThresholdCandidate. Bypasses the band in
	 * both currentWindowValue and replayFullHistory below, so an accepted candidate survives a
	 * full rebuild/replay instead of being rejected again against a moving reference. */
	private Set<String> acceptedActivityIds(User athlete, ThresholdField field) {
		return acceptedThresholdCandidateRepository.findByAthleteIdAndField(athlete.getId(), field).stream()
				.map(c -> c.getActivity().getId())
				.collect(Collectors.toSet());
	}

	/** The best qualifying candidate among the athlete's activities in the trailing window ending
	 * asOf (default today) for `field` - cheap, bounded to ~windowDays worth of activities. Used
	 * by both the ingest hook and the manual-refresh endpoint. Sanity-filters each candidate
	 * against the athlete's current profile value (not a moving reference - this is a single
	 * snapshot-in-time scan, not a chronological replay; see replayFullHistory for that), except
	 * for an explicitly accepted candidate (see acceptedActivityIds), which always qualifies. */
	public Candidate currentWindowValue(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		LocalDate windowStart = effectiveAsOf.minusDays(athlete.getThresholdWindowDays());
		Double referenceValue = athleteReferenceValue(athlete, field);
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);

		Instant start = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = effectiveAsOf.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end);

		Candidate best = null;
		for (Activity activity : activities) {
			Double implied = impliedValue(activity, athlete, field);
			boolean qualifies = implied != null && (acceptedIds.contains(activity.getId())
					|| withinSanityBand(implied, referenceValue, athlete.getThresholdSanityPct()));
			if (qualifies && (best == null || isBetter(field, implied, best.impliedValue()))) {
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
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);

		List<ThresholdHistoryEntry> entries = new ArrayList<>();
		List<Candidate> window = new ArrayList<>();
		Double currentValue = null;
		int total = activities.size();
		int current = 0;

		for (Activity activity : activities) {
			LocalDate activityDate = dateOf(activity);
			LocalDate cutoff = activityDate.minusDays(athlete.getThresholdWindowDays());
			window = window.stream().filter(c -> c.date().isAfter(cutoff)).collect(Collectors.toCollection(ArrayList::new));

			Double implied = impliedValue(activity, athlete, field);
			boolean qualifies = implied != null && (acceptedIds.contains(activity.getId())
					|| withinSanityBand(implied, currentValue, athlete.getThresholdSanityPct()));
			if (qualifies) {
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
					// currentFrom is *this* iteration's activityDate - the date whose recompute
					// pass discovered best as the new window winner - not best.date
					// (effectiveFrom), which can be much earlier when best only wins now because
					// a better, more recent entry aged out from under it.
					entries.add(new ThresholdHistoryEntry(field, newValue, best.activityId(), best.date(), activityDate));
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

	// --- Threshold suggestions: detection only (pure/read-only, like the rest of this class) ---
	// Response formatting, dismissal filtering, and caching live in ThresholdSuggestionService,
	// which is also where the AcceptedThresholdCandidate/ThresholdSuggestionDismissal
	// repositories get consumed for anything beyond the sanity-band bypass above.

	/** Qualifying efforts that *beat* the athlete's current value but were excluded by the
	 * sanity band (thresholdSanityPct) - a real breakthrough, or a recalibrated/corrupt sensor;
	 * the athlete decides (see the Threshold suggestions feature). Same trailing-window scan as
	 * currentWindowValue, but keeps exactly what that one excludes: upward outliers only (a
	 * corrupt-LOW reading is simply wrong, not an actionable "maybe you're fitter now"
	 * suggestion - withinSanityBand already treats it identically to a corrupt-high one, so the
	 * direction check here is what narrows to just the interesting case). Already-accepted
	 * candidates are excluded - they're part of the real ledger now, not a pending suggestion.
	 * Returns every qualifying candidate, best first. */
	public List<RejectedCandidate> rejectedCandidates(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		LocalDate windowStart = effectiveAsOf.minusDays(athlete.getThresholdWindowDays());
		Double referenceValue = athleteReferenceValue(athlete, field);
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);

		Instant start = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = effectiveAsOf.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end);

		List<RejectedCandidate> candidates = new ArrayList<>();
		for (Activity activity : activities) {
			if (!acceptedIds.contains(activity.getId())) {
				ImpliedValueDetail detail = impliedValueDetail(activity, athlete, field);
				if (detail != null && !withinSanityBand(detail.value(), referenceValue, athlete.getThresholdSanityPct())
						// Reaching here already proves referenceValue is a real, non-zero number -
						// the sanity band above only ever fails when it is (see withinSanityBand's
						// own null/0 passthrough).
						&& isBetter(field, detail.value(), referenceValue)) {
					candidates.add(new RejectedCandidate(
							activity.getId(), dateOf(activity), detail.value(), detail.window(), detail.rawValue()));
				}
			}
			entityManager.clear();
		}
		boolean lowerIsBetter = LOWER_IS_BETTER.contains(field);
		candidates.sort(lowerIsBetter
				? java.util.Comparator.comparingDouble(RejectedCandidate::impliedValue)
				: java.util.Comparator.comparingDouble(RejectedCandidate::impliedValue).reversed());
		return candidates;
	}

	/** How many days before a threshold's current source activity ages out of the window the
	 * Threshold suggestions feature warns the athlete - User.getThresholdWarningDays(), scaled
	 * down for an athlete with a shorter-than-typical window (otherwise a long lead on a short
	 * window could warn before the value has even had a real chance to establish itself). */
	public static int warningLeadDays(User athlete) {
		if (athlete.getThresholdWindowDays() >= 84) {
			return athlete.getThresholdWarningDays();
		}
		return athlete.getThresholdWindowDays() / 4;
	}

	/** Warns the athlete when the best effort behind the current threshold value is about to age
	 * out of the trailing window, before the value silently drops - enough lead time to test or
	 * race for a fresh one (see the Threshold suggestions feature). Returns null when there's
	 * nothing to warn about (not yet/no longer in the lead window, warnings off, or a small-drop/
	 * near-match suppression rule applies). */
	public UpcomingDropResult upcomingDrop(User athlete, ThresholdField field, LocalDate today) {
		LocalDate effectiveToday = today != null ? today : LocalDate.now();
		int lead = warningLeadDays(athlete);
		if (lead <= 0) {
			return null;
		}

		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		if (latest == null || ChronoUnit.DAYS.between(latest.getEffectiveFrom(), effectiveToday) > athlete.getThresholdWindowDays()) {
			return null;
		}

		// isStale flips the day *after* threshold_window_days have elapsed since effectiveFrom
		// (see ThresholdHistoryService.isStale's own ">" comparison), so the window's actual last
		// covered day is one later than a naive +thresholdWindowDays would give.
		LocalDate expiry = latest.getEffectiveFrom().plusDays(athlete.getThresholdWindowDays() + 1L);
		int daysLeft = (int) ChronoUnit.DAYS.between(effectiveToday, expiry);
		if (daysLeft <= 0 || daysLeft > lead) {
			return null;
		}

		Candidate successor = currentWindowValue(athlete, field, expiry);
		Double currentValue = athleteReferenceValue(athlete, field);

		// Rule 1: small drop - close enough that a warning isn't worth the noise.
		if (successor != null && currentValue != null && currentValue != 0) {
			double delta = Math.abs(successor.impliedValue() - currentValue);
			double deltaPct = delta / currentValue * 100;
			double smallAbsolute = LOWER_IS_BETTER.contains(field) ? 3 : 5;
			if (deltaPct <= 2 && delta < smallAbsolute) {
				return null;
			}
		}

		// Rule 2: near match - a recent activity (last 14 days) already implies a value close to
		// current, so it's likely to naturally replace it before the drop happens anyway.
		if (currentValue != null && currentValue != 0) {
			Instant recentStart = effectiveToday.minusDays(14).atStartOfDay(ZoneOffset.UTC).toInstant();
			Instant recentEnd = effectiveToday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			List<Activity> recentActivities =
					activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), recentStart, recentEnd);
			for (Activity activity : recentActivities) {
				Double implied = impliedValue(activity, athlete, field);
				entityManager.clear();
				if (implied != null && Math.abs(implied - currentValue) / currentValue <= 0.02) {
					return null;
				}
			}
		}

		// Rule 3: race booked - a race in the field's sport before expiry should naturally
		// refresh the value; the caller turns this into a quiet note instead of a warning.
		Race race = raceRepository
				.findFirstByAthleteIdAndSportAndDateGreaterThanEqualAndDateLessThanOrderByDateAsc(
						athlete.getId(), sportFor(field), effectiveToday, expiry)
				.orElse(null);
		if (race != null) {
			return new UpcomingDropResult.RaceWillRefresh(race, expiry, daysLeft);
		}

		// Rule 4: no successor - emit anyway (successor=null); the caller formats this as "goes
		// stale" rather than "drops to {value}".
		return new UpcomingDropResult.UpcomingDrop(latest, successor, expiry, daysLeft);
	}
}
