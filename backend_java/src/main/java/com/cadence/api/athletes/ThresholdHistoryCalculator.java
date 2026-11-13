package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.users.User;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

	public enum CandidateStatus {
		QUALIFIED, REJECTED_SANITY, ACCEPTED, TOO_SHORT,
		// One activity's implied-value computation threw - surfaced as this status with a reason
		// instead of failing list_threshold_candidates' whole call (issue #500).
		ERROR
	}

	/** Every one of an athlete's activities in a date range, classified against their *current*
	 * profile value - see {@link #candidateStatuses}. {@code impliedValue}/{@code rawValue}/
	 * {@code window} are null for a {@link CandidateStatus#TOO_SHORT} or {@link
	 * CandidateStatus#ERROR} activity (no qualifying effort computed either way).
	 * {@code errorReason} is null except for {@code ERROR}. */
	public record CandidateStatusEntry(String activityId, LocalDate date, CandidateStatus status,
			Double impliedValue, Double rawValue, String window, String errorReason) {

		public CandidateStatusEntry(
				String activityId, LocalDate date, CandidateStatus status, Double impliedValue, Double rawValue, String window) {
			this(activityId, date, status, impliedValue, rawValue, window, null);
		}
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

	static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	/** Like {@link #impliedValue}, but also returns the window label and the raw pre-transform
	 * value (e.g. the raw 20-min power before FTP's x0.95 multiplier) - used directly by the
	 * Threshold suggestions feature's "implies" copy for a rejected candidate; impliedValue below
	 * is a thin wrapper over this for every other, much more numerous caller that only ever
	 * wanted the final value. */
	record ImpliedValueDetail(String window, double rawValue, double value) {
	}

	/** Fetches this one activity's records itself - for a caller considering activities one at a
	 * time where that's cheap (replayFullHistory's whole-history replay, upcomingDrop's 14-day
	 * recent-match check): each already bounds how many activities/records it touches on its
	 * own, so a dedicated per-activity query is fine there. A caller scanning many activities in
	 * one go (rejectedCandidates, candidateStatuses, currentWindowValue) should batch-fetch with
	 * {@link #batchedRecordsFor} instead and call the other overload below - see its own Javadoc
	 * for why a per-activity query doesn't scale the same way. */
	ImpliedValueDetail impliedValueDetail(Activity activity, User athlete, ThresholdField field) {
		return impliedValueDetail(
				activity, athlete, field, recordRepository.findPowerTimeDistanceByActivityIdOrderByT(activity.getId()));
	}

	ImpliedValueDetail impliedValueDetail(
			Activity activity, User athlete, ThresholdField field, List<? extends RecordRepository.PowerTimeDistance> records) {
		if (records.isEmpty()) {
			return null;
		}
		List<Integer> powerSeries = RunningPowerSanitizer.sanitize(records.stream()
				.map(RecordRepository.PowerTimeDistance::getPower).toList(), activity.getSport(), athlete.getMaxRunningPowerWatts());
		List<Integer> tSeries = records.stream().map(RecordRepository.PowerTimeDistance::getT).toList();
		List<Double> distanceKmSeries = records.stream().map(RecordRepository.PowerTimeDistance::getDistanceKm).toList();

		return switch (field) {
			case FTP -> {
				// Issue #499/#503 phase 1: excluded if this activity's attached-primary power
				// source disagrees with the athlete's *current* bikePowerSource preference - same
				// re-check-on-every-recompute pattern as CRITICAL_RUN_POWER below, but ANY (the
				// default) always matches, and a null bikePowerSource (never had a second source
				// attached - the common case) also always matches.
				if (!activity.matchesBikePowerPreference(athlete)) {
					yield null;
				}
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

	// Chunk size for batchedRecordsFor below - one query per chunk instead of one per activity
	// (issue #500), but still small enough that a chunk's worth of 1 Hz streams held in memory at
	// once stays bounded, unlike fetching a whole multi-hundred-activity request's records in one
	// shot (tried first - OOM'd a test JVM on a real year's worth of this athlete's rides, trading
	// the per-activity partition-fanout cost for an unbounded batch-memory one instead).
	private static final int RECORD_BATCH_SIZE = 20;

	List<List<Activity>> chunked(List<Activity> activities) {
		List<List<Activity>> chunks = new ArrayList<>();
		for (int i = 0; i < activities.size(); i += RECORD_BATCH_SIZE) {
			chunks.add(activities.subList(i, Math.min(i + RECORD_BATCH_SIZE, activities.size())));
		}
		return chunks;
	}

	/** One query for every activity in `chunk`'s records, instead of one per activity (issue
	 * #500) - see {@link com.cadence.api.activities.RecordRepository#findPowerTimeDistanceByActivityIdInAndTsBetween}
	 * for why a per-activity query doesn't scale here. `chunk` should be one of {@link #chunked}'s
	 * pieces, not a whole request's activities at once - the ts bound below is derived from the
	 * chunk's own (startDate-ordered) activities, both to keep partition pruning tight and to
	 * bound how many activities' streams are held in memory at once (see {@link
	 * #RECORD_BATCH_SIZE}'s own Javadoc). */
	Map<String, List<RecordRepository.PowerTimeDistanceRow>> batchedRecordsFor(List<Activity> chunk) {
		if (chunk.isEmpty()) {
			return Map.of();
		}
		List<String> activityIds = chunk.stream().map(Activity::getId).toList();
		// chunk is startDate-ordered (findForThresholdWindow's own "order by a.startDate"), so the
		// first/last give the span cheaply - minusDays(1)/plusDays(2) comfortably covers any single
		// activity's own duration on either edge.
		Instant chunkStart = chunk.get(0).getStartDate().minus(Duration.ofDays(1));
		Instant chunkEnd = chunk.get(chunk.size() - 1).getStartDate().plus(Duration.ofDays(2));
		return recordRepository.findPowerTimeDistanceByActivityIdInAndTsBetween(activityIds, chunkStart, chunkEnd).stream()
				.collect(Collectors.groupingBy(RecordRepository.PowerTimeDistanceRow::getActivityId));
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

	static LocalDate dateOf(Activity activity) {
		return activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
	}

	/** Activity ids the athlete has explicitly accepted as real despite failing the sanity band -
	 * see the Threshold suggestions feature's AcceptedThresholdCandidate. Bypasses the band in
	 * both currentWindowValue and replayFullHistory below, so an accepted candidate survives a
	 * full rebuild/replay instead of being rejected again against a moving reference. */
	Set<String> acceptedActivityIds(User athlete, ThresholdField field) {
		return acceptedThresholdCandidateRepository.findByAthleteIdAndField(athlete.getId(), field).stream()
				.map(c -> c.getActivity().getId())
				.collect(Collectors.toSet());
	}

	/** A manual value lasts until it's beaten by a later qualifying activity or ages out of the
	 * window, same as any other ledger entry (issue #484) - currentWindowValue's activity-only
	 * scan can't see the ledger on its own, so a still-fresh manual row is injected here as a
	 * candidate with its own value, to compete on equal terms. Returns null for a measured
	 * anchor: its source activity is already in the activities scan and wins there on its own. */
	private Candidate manualAnchorCandidate(User athlete, ThresholdField field, LocalDate windowStart) {
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
				.orElse(null);
		if (latest == null || latest.getKind() != ThresholdHistoryKind.MANUAL
				|| latest.getEffectiveFrom().isBefore(windowStart)) {
			return null;
		}
		Double manualValue = rowValue(field, latest);
		return manualValue == null ? null : new Candidate(null, latest.getEffectiveFrom(), manualValue);
	}

	/** A ledger row's own value as a plain double, regardless of field (pace is stored as "M:SS",
	 * every other field as a plain number) - shared by manualAnchorCandidate above and
	 * replayFullHistory's manual-row merge below. */
	private static Double rowValue(ThresholdField field, ThresholdHistory row) {
		return field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(row.getValuePace())
				: row.getValueNumeric() == null ? null : row.getValueNumeric().doubleValue();
	}

	/** The best qualifying candidate among the athlete's activities in the trailing window ending
	 * asOf (default today) for `field` - cheap, bounded to ~windowDays worth of activities. Used
	 * by both the ingest hook and the manual-refresh endpoint. Sanity-filters each candidate
	 * against the athlete's current profile value (not a moving reference - this is a single
	 * snapshot-in-time scan, not a chronological replay; see replayFullHistory for that), except
	 * for an explicitly accepted candidate (see acceptedActivityIds), which always qualifies.
	 * Also considers the latest manual entry, if still within the window, as a candidate in its
	 * own right (see manualAnchorCandidate) - otherwise it's invisible here and gets replaced by
	 * the first later activity regardless of value, however low (issue #484). */
	public Candidate currentWindowValue(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		LocalDate windowStart = effectiveAsOf.minusDays(athlete.getThresholdWindowDays());
		Double referenceValue = athleteReferenceValue(athlete, field);
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);

		Instant start = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = effectiveAsOf.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end);

		Candidate best = manualAnchorCandidate(athlete, field, windowStart);
		for (List<Activity> chunk : chunked(activities)) {
			Map<String, List<RecordRepository.PowerTimeDistanceRow>> batchedRecords = batchedRecordsFor(chunk);
			for (Activity activity : chunk) {
				ImpliedValueDetail detail = impliedValueDetail(
						activity, athlete, field, batchedRecords.getOrDefault(activity.getId(), List.of()));
				Double implied = detail == null ? null : detail.value();
				boolean qualifies = implied != null && (acceptedIds.contains(activity.getId())
						|| withinSanityBand(implied, referenceValue, athlete.getThresholdSanityPct()));
				if (qualifies && (best == null || isBetter(field, implied, best.impliedValue()))) {
					best = new Candidate(activity.getId(), dateOf(activity), implied);
				}
				// Load-bearing, not cosmetic (see ExportWriter's Javadoc for the same pattern):
				// Hibernate's persistence context pins every Activity loaded here for the life of
				// the enclosing transaction. Bounded to ~windowDays worth of activities, but a
				// prolific athlete's trailing window can still be large enough to matter.
				entityManager.clear();
			}
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
	 * <p>Every still-current (not correction-only) manual row is merged into the same window at
	 * its own effectiveFrom, exactly like manualAnchorCandidate's single as-of snapshot but
	 * repeated at every point in the replay (issue #504) - rebuildHistory only deletes MEASURED
	 * rows before calling this, so a manual row's own ledger entry is never rewritten here; this
	 * only makes sure a *measured* activity dated after it is correctly suppressed/revealed
	 * relative to it, the same #484 rule currentWindowValue already gets via manualAnchorCandidate.
	 * A correction-only row is deliberately excluded from this merge - it never competed to be
	 * current, so it shouldn't start winning the window on replay either.
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
		List<Activity> activities = activityRepository.findByAthleteIdAndSportAndPrimaryActivityIsNullOrderByStartDate(athlete.getId(), sportFor(field));
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);
		List<ThresholdHistory> manualRows = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(athlete.getId(), field, ThresholdHistoryKind.MANUAL);
		int nextManualIdx = 0;

		List<ThresholdHistoryEntry> entries = new ArrayList<>();
		List<Candidate> window = new ArrayList<>();
		Double currentValue = null;
		int total = activities.size();
		int current = 0;

		for (Activity activity : activities) {
			LocalDate activityDate = dateOf(activity);

			// Merge in every manual row whose effectiveFrom has arrived as of this activity,
			// before this activity's own candidacy is decided - it has to compete against (and
			// potentially be suppressed by) a manual row dated on or before it, same ordering
			// currentWindowValue already gets for free from a single as-of snapshot.
			while (nextManualIdx < manualRows.size() && !manualRows.get(nextManualIdx).getEffectiveFrom().isAfter(activityDate)) {
				ThresholdHistory manualRow = manualRows.get(nextManualIdx++);
				Double manualValue = rowValue(field, manualRow);
				if (manualValue != null) {
					window = window.stream()
							.filter(c -> !isBetter(field, manualValue, c.impliedValue()))
							.collect(Collectors.toCollection(ArrayList::new));
					// activityId null, same as manualAnchorCandidate - this candidate's ledger
					// row already exists (it wasn't deleted), so no entry is written for it below.
					window.add(new Candidate(null, manualRow.getEffectiveFrom(), manualValue));
				}
			}

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
				// Only a measured winner (a real activity) needs a fresh entry - a manual
				// winner's own row already exists in the ledger untouched (see this method's own
				// Javadoc), so writing one here would duplicate it.
				if (best != null && best.activityId() != null) {
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

	/** As {@link #replayFullHistory}, but replays only from {@code fromDate} onward (issue
	 * #545) - a duplicate-status change on an activity dated d can only alter the ledger from d
	 * onward, so this re-derives just that tail instead of replaying the athlete's whole history.
	 * Returns exactly the entries {@code replayFullHistory} would emit with currentFrom &gt;=
	 * fromDate; rows dated before that are the caller's to leave in place (see
	 * {@code ThresholdHistoryService.rebuildHistoryFrom}'s delete predicate).
	 *
	 * <p><b>Seeding contract.</b> The replay's state at any date is its sliding window of
	 * not-yet-expired, not-yet-dominated candidates plus the moving sanity reference (the
	 * window's best). At the first date &gt;= fromDate every candidate still in that window is
	 * dated after {@code fromDate - thresholdWindowDays} (older ones have aged out by
	 * definition), so the scan below covers every candidate that can matter in the tail. The
	 * part of the window dated before the scan start is seeded from the ledger itself: every
	 * MEASURED row and every surviving (not correction-only) MANUAL row with effectiveFrom
	 * before the scan start re-enters as a window candidate at its own effectiveFrom. That is
	 * exact, not an approximation: a pre-scan candidate that never became current (and so has no
	 * row of its own) can only matter in the tail if it later becomes the window's best, which
	 * is impossible while a better candidate outlives it - and any such better candidate either
	 * has its own row (seeded here, dated later, so it outlives the shadowed one) or is itself
	 * a scan candidate that re-removes it on arrival. A pre-scan candidate rejected by the
	 * sanity band never entered the window in the first place, so it's absent from both.
	 *
	 * <p>The moving reference is initialised to the seeded window's best (aged as of the first
	 * scanned activity) and then tracked exactly like {@code replayFullHistory}'s - including
	 * updating it when a manual candidate takes over without emitting an entry. Entries are only
	 * emitted for dates &gt;= fromDate: value changes discovered during the pre-fromDate part of
	 * the scan correspond to rows that already exist with currentFrom &lt; fromDate and were not
	 * deleted, so re-emitting them would duplicate. */
	public List<ThresholdHistoryEntry> replayFrom(User athlete, ThresholdField field, LocalDate fromDate) {
		return replayFrom(athlete, field, fromDate, (current, total) -> { });
	}

	public List<ThresholdHistoryEntry> replayFrom(User athlete, ThresholdField field, LocalDate fromDate,
			BiConsumer<Integer, Integer> onProgress) {
		int windowDays = athlete.getThresholdWindowDays();
		LocalDate scanStart = fromDate.minusDays(windowDays);

		List<Candidate> window = new ArrayList<>();
		// Seed: pre-scan measured rows, oldest first (see the seeding contract above).
		for (ThresholdHistory row : thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndEffectiveFromLessThanOrderByEffectiveFromAsc(
						athlete.getId(), field, ThresholdHistoryKind.MEASURED, scanStart)) {
			Double value = rowValue(field, row);
			if (value != null) {
				window.add(new Candidate(row.getSourceActivity().getId(), row.getEffectiveFrom(), value));
			}
		}
		// Seed: pre-scan manual rows (surviving ones only - a correction-only row never competed
		// to be current, same exclusion replayFullHistory's own merge applies). The scan-merge
		// list below picks up the rest at their own effectiveFrom, exactly like replayFullHistory.
		List<ThresholdHistory> manualRows = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(athlete.getId(), field,
						ThresholdHistoryKind.MANUAL);
		int nextManualIdx = 0;
		for (; nextManualIdx < manualRows.size() && !manualRows.get(nextManualIdx).getEffectiveFrom().isAfter(scanStart);
				nextManualIdx++) {
			ThresholdHistory manualRow = manualRows.get(nextManualIdx);
			Double manualValue = rowValue(field, manualRow);
			if (manualValue != null) {
				window.add(new Candidate(null, manualRow.getEffectiveFrom(), manualValue));
			}
		}

		Set<String> acceptedIds = acceptedActivityIds(athlete, field);
		Instant start = scanStart.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities = activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end);
		if (activities.isEmpty()) {
			return List.of();
		}

		// The moving sanity reference as of the first scanned activity: apply that date's own
		// manual merge and aging pass to the seeded window first (a manual row dated between the
		// scan start and the first activity could be the better reference), then take its best.
		LocalDate firstDate = dateOf(activities.get(0));
		while (nextManualIdx < manualRows.size() && !manualRows.get(nextManualIdx).getEffectiveFrom().isAfter(firstDate)) {
			ThresholdHistory manualRow = manualRows.get(nextManualIdx++);
			Double manualValue = rowValue(field, manualRow);
			if (manualValue != null) {
				window = window.stream()
						.filter(c -> !isBetter(field, manualValue, c.impliedValue()))
						.collect(Collectors.toCollection(ArrayList::new));
				window.add(new Candidate(null, manualRow.getEffectiveFrom(), manualValue));
			}
		}
		LocalDate initialCutoff = firstDate.minusDays(windowDays);
		window = window.stream().filter(c -> c.date().isAfter(initialCutoff)).collect(Collectors.toCollection(ArrayList::new));
		Double currentValue = window.isEmpty() ? null : window.get(0).impliedValue();

		List<ThresholdHistoryEntry> entries = new ArrayList<>();
		int total = activities.size();
		int current = 0;

		for (Activity activity : activities) {
			LocalDate activityDate = dateOf(activity);

			// Merge in every manual row whose effectiveFrom has arrived as of this activity -
			// same ordering and effect as replayFullHistory's own merge (the pre-scan ones were
			// already seeded above, so the list starts at the first post-seed row).
			while (nextManualIdx < manualRows.size() && !manualRows.get(nextManualIdx).getEffectiveFrom().isAfter(activityDate)) {
				ThresholdHistory manualRow = manualRows.get(nextManualIdx++);
				Double manualValue = rowValue(field, manualRow);
				if (manualValue != null) {
					window = window.stream()
							.filter(c -> !isBetter(field, manualValue, c.impliedValue()))
							.collect(Collectors.toCollection(ArrayList::new));
					window.add(new Candidate(null, manualRow.getEffectiveFrom(), manualValue));
				}
			}

			LocalDate cutoff = activityDate.minusDays(windowDays);
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
				// Only a measured winner (a real activity) needs an entry - and only from
				// fromDate onward: a change discovered earlier corresponds to a row that already
				// exists with currentFrom < fromDate and was deliberately left in place. A manual
				// winner's own row already exists untouched, same as replayFullHistory.
				if (best != null && best.activityId() != null && !activityDate.isBefore(fromDate)) {
					entries.add(new ThresholdHistoryEntry(field, newValue, best.activityId(), best.date(), activityDate));
				}
			}
			current++;
			onProgress.accept(current, total);
			// Same load-bearing entityManager.clear() as replayFullHistory - see its own comment.
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
		for (List<Activity> chunk : chunked(activities)) {
			Map<String, List<RecordRepository.PowerTimeDistanceRow>> batchedRecords = batchedRecordsFor(chunk);
			for (Activity activity : chunk) {
				if (!acceptedIds.contains(activity.getId())) {
					ImpliedValueDetail detail;
					try {
						detail = impliedValueDetail(
								activity, athlete, field, batchedRecords.getOrDefault(activity.getId(), List.of()));
					}
					catch (Exception e) {
						// One bad activity's stream shouldn't take down the whole suggestions scan
						// - skip it and keep going (issue #500's class of bug, applied defensively
						// here too even without a reproduced trigger for it).
						detail = null;
					}
					if (detail != null && !withinSanityBand(detail.value(), referenceValue, athlete.getThresholdSanityPct())
							// Reaching here already proves referenceValue is a real, non-zero
							// number - the sanity band above only ever fails when it is (see
							// withinSanityBand's own null/0 passthrough).
							&& isBetter(field, detail.value(), referenceValue)) {
						candidates.add(new RejectedCandidate(
								activity.getId(), dateOf(activity), detail.value(), detail.window(), detail.rawValue()));
					}
				}
				entityManager.clear();
			}
		}
		boolean lowerIsBetter = LOWER_IS_BETTER.contains(field);
		candidates.sort(lowerIsBetter
				? java.util.Comparator.comparingDouble(RejectedCandidate::impliedValue)
				: java.util.Comparator.comparingDouble(RejectedCandidate::impliedValue).reversed());
		return candidates;
	}

	/** Every one of the athlete's activities of {@code field}'s sport in [from, to], classified
	 * against the athlete's *current* profile value - a single snapshot, not a moving reference
	 * (see {@link #currentWindowValue}'s own Javadoc for that distinction): {@code QUALIFIED}
	 * (within the sanity band), {@code REJECTED_SANITY} (outside it), {@code ACCEPTED}
	 * (explicitly accepted despite failing the band - see {@code AcceptedThresholdCandidate}), or
	 * {@code TOO_SHORT} (no qualifying effort at all, e.g. too short for the field's window).
	 * Unlike {@link #rejectedCandidates}, returns every activity in range, not just the
	 * rejected-and-better subset - built for the Threshold suggestions calibration tooling
	 * (list_threshold_candidates), to see what a model would have had to predict between ledger
	 * entries. A per-activity computation failure is surfaced as {@code CandidateStatus.ERROR}
	 * with {@code errorReason} set, rather than failing the whole call (issue #500). */
	public List<CandidateStatusEntry> candidateStatuses(User athlete, ThresholdField field, LocalDate from, LocalDate to) {
		return candidateStatuses(athlete, field, from, to, false);
	}

	/** As {@link #candidateStatuses(User, ThresholdField, LocalDate, LocalDate)}, but
	 * {@code includeDuplicates} (issue #498) can also classify a same-ride-twice duplicate copy -
	 * only the MCP list_threshold_candidates tool's ad-hoc inspection exposes this; every other
	 * caller leaves it at the default, which excludes a duplicate exactly like currentWindowValue/
	 * rejectedCandidates do. */
	public List<CandidateStatusEntry> candidateStatuses(
			User athlete, ThresholdField field, LocalDate from, LocalDate to, boolean includeDuplicates) {
		Double referenceValue = athleteReferenceValue(athlete, field);
		Set<String> acceptedIds = acceptedActivityIds(athlete, field);

		Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, end, includeDuplicates);

		List<CandidateStatusEntry> statuses = new ArrayList<>();
		for (List<Activity> chunk : chunked(activities)) {
			Map<String, List<RecordRepository.PowerTimeDistanceRow>> batchedRecords = batchedRecordsFor(chunk);
			for (Activity activity : chunk) {
				LocalDate activityDate = dateOf(activity);
				ImpliedValueDetail detail;
				try {
					detail = impliedValueDetail(activity, athlete, field, batchedRecords.getOrDefault(activity.getId(), List.of()));
				}
				catch (Exception e) {
					statuses.add(new CandidateStatusEntry(
							activity.getId(), activityDate, CandidateStatus.ERROR, null, null, null, e.toString()));
					entityManager.clear();
					continue;
				}
				if (detail == null) {
					statuses.add(new CandidateStatusEntry(activity.getId(), activityDate, CandidateStatus.TOO_SHORT, null, null, null));
				}
				else {
					CandidateStatus status = acceptedIds.contains(activity.getId()) ? CandidateStatus.ACCEPTED
							: withinSanityBand(detail.value(), referenceValue, athlete.getThresholdSanityPct()) ? CandidateStatus.QUALIFIED
							: CandidateStatus.REJECTED_SANITY;
					statuses.add(new CandidateStatusEntry(
							activity.getId(), activityDate, status, detail.value(), detail.rawValue(), detail.window()));
				}
				entityManager.clear();
			}
		}
		return statuses;
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
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
				.orElse(null);
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
