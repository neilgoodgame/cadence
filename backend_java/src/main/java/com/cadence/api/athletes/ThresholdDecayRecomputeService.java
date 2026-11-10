package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suffix resimulation: the design doc's "one mechanism" (docs/plans/threshold-decay.md section
 * 4.2) for every threshold-decay trigger (ingest, manual edit, refresh, rebuild) - re-simulates a
 * field's ledger from {@code fromDate} through {@code end} via {@link ThresholdDecay#simulate},
 * replacing whatever {@code MEASURED}/{@code DECAYED} rows already cover that range. Only ever
 * called for a decay-enabled athlete; a decay-disabled one keeps going through {@link
 * ThresholdHistoryService}/{@link ThresholdHistoryCalculator}'s existing currentWindowValue/
 * recomputeAndRecord/replayFullHistory entirely unchanged - see this class's callers there.
 * Matches the Python backend's athletes/threshold_decay_recompute.py.
 */
@Service
public class ThresholdDecayRecomputeService {

	public record ResimulateResult(boolean changed, int reratedCount, List<ThresholdHistoryService.RerateDiff> wouldRerate) {
		public ResimulateResult(boolean changed) {
			this(changed, 0, List.of());
		}
	}

	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ThresholdHistoryCalculator calculator;
	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final UserRepository userRepository;
	private final DecayLoadService decayLoadService;
	private final TssRecomputeService tssRecomputeService;

	public ThresholdDecayRecomputeService(ThresholdHistoryRepository thresholdHistoryRepository,
			ThresholdHistoryCalculator calculator, ActivityRepository activityRepository, RecordRepository recordRepository,
			UserRepository userRepository, DecayLoadService decayLoadService, TssRecomputeService tssRecomputeService) {
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.calculator = calculator;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.userRepository = userRepository;
		this.decayLoadService = decayLoadService;
		this.tssRecomputeService = tssRecomputeService;
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	private static double rowValue(ThresholdField field, ThresholdHistory row) {
		return field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(row.getValuePace()) : row.getValueNumeric();
	}

	private static Double mmssToSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(":");
		try {
			return (double) (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String secondsToMmss(double seconds) {
		int total = (int) Math.round(seconds);
		return "%d:%02d".formatted(total / 60, total % 60);
	}

	private static Instant startOfDay(LocalDate d) {
		return d.atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	private ThresholdDecay.DecaySettings decaySettingsFor(User athlete) {
		return new ThresholdDecay.DecaySettings(athlete.getThresholdWindowDays(), athlete.getThresholdSanityPct(),
				athlete.getThresholdDecayTauDays(), athlete.getThresholdDecayMinPct(), athlete.getThresholdDecayMaxPct(),
				athlete.isThresholdDecayEnabled());
	}

	/** The ledger's current value as of {@code fromDate} minus one day - any kind, including
	 * decayed. */
	private Double seedV(User athlete, ThresholdField field, LocalDate fromDate) {
		return thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCurrentFromLessThanOrderByCurrentFromDescIdDesc(athlete.getId(), field, fromDate)
				.map(row -> rowValue(field, row))
				.orElse(null);
	}

	private record SeedAnchor(Double value, LocalDate date, ThresholdHistoryKind kind) {
	}

	/** The latest non-decayed row before {@code fromDate} - the anchor simulate() decays away
	 * from. */
	private SeedAnchor seedAnchor(User athlete, ThresholdField field, LocalDate fromDate) {
		return thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndEffectiveFromLessThanAndKindNotOrderByEffectiveFromDescIdDesc(
						athlete.getId(), field, fromDate, ThresholdHistoryKind.DECAYED)
				.map(row -> new SeedAnchor(rowValue(field, row), row.getEffectiveFrom(), row.getKind()))
				.orElse(new SeedAnchor(null, null, null));
	}

	/** Every qualifying activity from windowDays before {@code fromDate} (to seed the successor
	 * pool) through {@code end} - same scan {@code currentWindowValue}/{@code
	 * replayFullHistory} already use, just reused here rather than re-implemented. */
	private List<ThresholdDecay.DecayCandidate> gatherCandidates(
			User athlete, ThresholdField field, LocalDate fromDate, LocalDate end) {
		LocalDate windowStart = fromDate.minusDays(athlete.getThresholdWindowDays());
		Instant start = startOfDay(windowStart);
		Instant endInstant = startOfDay(end.plusDays(1));
		List<Activity> activities = activityRepository.findForThresholdWindow(athlete.getId(), sportFor(field), start, endInstant);
		Set<String> acceptedIds = calculator.acceptedActivityIds(athlete, field);

		List<ThresholdDecay.DecayCandidate> candidates = new ArrayList<>();
		for (List<Activity> chunk : calculator.chunked(activities)) {
			Map<String, List<RecordRepository.PowerTimeDistanceRow>> batched = calculator.batchedRecordsFor(chunk);
			for (Activity activity : chunk) {
				var detail = calculator.impliedValueDetail(activity, athlete, field, batched.getOrDefault(activity.getId(), List.of()));
				if (detail == null) {
					continue;
				}
				candidates.add(new ThresholdDecay.DecayCandidate(ThresholdHistoryCalculator.dateOf(activity), detail.value(),
						activity.getId(), acceptedIds.contains(activity.getId())));
			}
		}
		return candidates;
	}

	/** Every surviving (not correction-only) manual row in [fromDate, end], marked
	 * alreadyRecorded=true so simulate() respects its anchor-reset effect without duplicating it
	 * - plus the one brand-new entry a manual-edit trigger is itself recording, if any. */
	private List<ThresholdDecay.ManualEntry> gatherManualEntries(
			User athlete, ThresholdField field, LocalDate fromDate, LocalDate end, ThresholdDecay.ManualEntry newEntry) {
		List<ThresholdDecay.ManualEntry> entries = new ArrayList<>();
		for (ThresholdHistory row : thresholdHistoryRepository.findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseAndEffectiveFromBetween(
				athlete.getId(), field, ThresholdHistoryKind.MANUAL, fromDate, end)) {
			entries.add(new ThresholdDecay.ManualEntry(row.getEffectiveFrom(), rowValue(field, row), null, true));
		}
		if (newEntry != null) {
			entries.add(newEntry);
		}
		return entries;
	}

	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate) {
		return resimulateSuffix(athlete, field, fromDate, LocalDate.now(), null, null, true, false);
	}

	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end) {
		return resimulateSuffix(athlete, field, fromDate, end, null, null, true, false);
	}

	/** Re-simulates {@code field}'s ledger from {@code fromDate} through {@code end} and
	 * persists the result - the single operation behind every decay-enabled trigger (ingest,
	 * manual edit, refresh, rebuild; see {@link ThresholdHistoryService}'s own wiring). Deleting
	 * MEASURED/DECAYED rows with currentFrom &gt;= fromDate then re-inserting simulate()'s fresh
	 * Steps implements every row of the design doc's 4.2 table uniformly, including "append
	 * only" (refresh/the future daily job), since nothing exists at/after fromDate yet in that
	 * case - the delete simply matches nothing.
	 *
	 * <p>{@code newManualEntry}/{@code manualSetBy} are for the manual-edit trigger only: the one
	 * brand-new entry being recorded becomes part of this same simulate() call (via {@code
	 * ManualEntry.alreadyRecorded() == false}) rather than a separate direct write, and {@code
	 * manualSetBy} is attached to whichever resulting Step is that entry's own.
	 *
	 * <p>Runs inside a transaction holding a row lock on {@code athlete} for its duration (design
	 * doc 4.3) - guards against two triggers re-simulating the same field at once. */
	@Transactional
	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end,
			ThresholdDecay.ManualEntry newManualEntry, User manualSetBy, boolean rerateActivities, boolean preview) {
		userRepository.findWithLockById(athlete.getId());

		ThresholdDecay.DecaySettings settings = decaySettingsFor(athlete);
		Double seedV = seedV(athlete, field, fromDate);
		SeedAnchor anchor = seedAnchor(athlete, field, fromDate);

		List<ThresholdDecay.DecayCandidate> candidates = gatherCandidates(athlete, field, fromDate, end);
		List<ThresholdDecay.ManualEntry> manualEntries = gatherManualEntries(athlete, field, fromDate, end, newManualEntry);

		Map<LocalDate, Double> loadByDay = anchor.value() != null
				? decayLoadService.loadByDayForAthlete(athlete, field, anchor.value(),
						athlete.getLthr() != null ? athlete.getLthr().doubleValue() : null,
						fromDate.minusDays(settings.windowDays()), end)
				: Map.of();

		List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(field, settings, anchor.value(), anchor.date(),
				anchor.kind() != null ? anchor.kind().wireValue() : null, candidates, manualEntries, loadByDay, fromDate, end, seedV);

		thresholdHistoryRepository.deleteByAthleteIdAndFieldAndCurrentFromGreaterThanEqualAndKindIn(
				athlete.getId(), field, fromDate, List.of(ThresholdHistoryKind.MEASURED, ThresholdHistoryKind.DECAYED));

		for (ThresholdDecay.Step step : steps) {
			ThresholdHistory row = new ThresholdHistory();
			row.setAthlete(athlete);
			row.setField(field);
			if (field == ThresholdField.THRESHOLD_PACE) {
				row.setValuePace(secondsToMmss(step.value()));
			}
			else {
				row.setValueNumeric((int) Math.round(step.value()));
			}
			if (step.activityId() != null) {
				row.setSourceActivity(activityRepository.getReferenceById(step.activityId()));
			}
			if ("manual".equals(step.kind()) && newManualEntry != null && step.currentFrom().equals(newManualEntry.date())) {
				row.setSetBy(manualSetBy);
			}
			row.setEffectiveFrom(step.effectiveFrom());
			row.setCurrentFrom(step.currentFrom());
			row.setKind(ThresholdHistoryKind.valueOf(step.kind().toUpperCase()));
			thresholdHistoryRepository.save(row);
		}

		boolean changed = !steps.isEmpty();
		int reratedCount = 0;
		List<ThresholdHistoryService.RerateDiff> wouldRerate = List.of();
		if (changed) {
			ThresholdHistory current = thresholdHistoryRepository
					.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
					.orElse(null);
			switch (field) {
				case FTP -> athlete.setFtp(current != null ? current.getValueNumeric() : null);
				case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower(current != null ? current.getValueNumeric() : null);
				case THRESHOLD_PACE -> athlete.setThresholdPace(current != null ? current.getValuePace() : null);
			}
			userRepository.save(athlete);

			// Load-bearing, not cosmetic (design doc 4.6) - without this, stored TSS/IF silently
			// drift from the ledger the moment a retroactive change happens (the exact failure
			// mode 4.4 calls out: "compute-on-read doesn't fit... TSS would still be built from
			// the stale value"). rerateActivities/preview only matter for the manual-edit
			// trigger; every other trigger leaves them at the defaults.
			if (rerateActivities || preview) {
				List<ThresholdHistoryService.RerateDiff> diffs = new ArrayList<>();
				List<Activity> toRerate =
						activityRepository.findForReRateAfterThresholdEdit(athlete.getId(), sportFor(field), startOfDay(fromDate));
				for (Activity activity : toRerate) {
					Double oldIntensity = activity.getIntensity();
					int oldTss = activity.getTss();
					if (!tssRecomputeService.rate(activity, athlete)) {
						continue;
					}
					if (preview) {
						diffs.add(new ThresholdHistoryService.RerateDiff(activity.getId(), ThresholdHistoryCalculator.dateOf(activity),
								oldIntensity, activity.getIntensity(), oldTss, activity.getTss()));
					}
					if (rerateActivities) {
						activityRepository.save(activity);
						reratedCount++;
					}
				}
				wouldRerate = diffs;
			}
		}

		return new ResimulateResult(changed, reratedCount, wouldRerate);
	}
}
