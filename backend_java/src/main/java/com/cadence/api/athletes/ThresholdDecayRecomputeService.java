package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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

	public record ResimulateResult(boolean changed, int reratedCount, List<ThresholdHistoryService.RerateDiff> wouldRerate,
			List<ThresholdHistoryService.QualificationDiff> wouldRequalify) {
		public ResimulateResult(boolean changed) {
			this(changed, 0, List.of(), List.of());
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

	/** Public (unlike this class's other static helpers) so previewThresholdDecay's MCP tool -
	 * in a different package, with no other reason to depend on this service's internals - can
	 * format a raw Step.value() the same way a real resimulation's own ThresholdHistory.valuePace
	 * would be. */
	public static String secondsToMmss(double seconds) {
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

	/** Every correction-only manual row up to {@code end} (issue #605) - simulate() uses them only
	 * as sanity-band references, never as anchors. Includes rows before fromDate on purpose: the
	 * latest of those can still be the live reference when the resimulation starts. */
	private List<ThresholdDecay.ManualEntry> gatherCorrections(User athlete, ThresholdField field, LocalDate end) {
		List<ThresholdDecay.ManualEntry> corrections = new ArrayList<>();
		for (ThresholdHistory row : thresholdHistoryRepository.findByAthleteIdAndFieldAndKindAndCorrectionOnlyTrueOrderByEffectiveFromAscIdAsc(
				athlete.getId(), field, ThresholdHistoryKind.MANUAL)) {
			if (!row.getEffectiveFrom().isAfter(end)) {
				corrections.add(new ThresholdDecay.ManualEntry(row.getEffectiveFrom(), rowValue(field, row)));
			}
		}
		return corrections;
	}

	/** Gathers every input from the real ledger and runs simulate() - the read-only half shared
	 * by resimulateSuffix, gatherPreviewSteps and simulateSuffixSteps, so they can never
	 * disagree about what a resimulation would produce. */
	private List<ThresholdDecay.Step> simulateSuffix(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end,
			ThresholdDecay.DecaySettings settings, ThresholdDecay.ManualEntry newManualEntry) {
		Double seedV = seedV(athlete, field, fromDate);
		SeedAnchor anchor = seedAnchor(athlete, field, fromDate);

		List<ThresholdDecay.DecayCandidate> candidates = gatherCandidates(athlete, field, fromDate, end);
		List<ThresholdDecay.ManualEntry> manualEntries = gatherManualEntries(athlete, field, fromDate, end, newManualEntry);
		List<ThresholdDecay.ManualEntry> corrections = gatherCorrections(athlete, field, end);

		Map<LocalDate, Double> loadByDay = anchor.value() != null
				? decayLoadService.loadByDayForAthlete(athlete, field, anchor.value(),
						athlete.getLthr() != null ? athlete.getLthr().doubleValue() : null,
						fromDate.minusDays(settings.windowDays()), end)
				: Map.of();

		return ThresholdDecay.simulate(field, settings, anchor.value(), anchor.date(),
				anchor.kind() != null ? anchor.kind().wireValue() : null, candidates, manualEntries, loadByDay, fromDate, end,
				seedV, corrections);
	}

	/** Read-only: the Steps a resimulation from {@code fromDate} would write against the ledger
	 * as it stands right now, with the athlete's own settings. recordManualValue calls it either
	 * side of saving a correction-only row to get a before/after pair for {@link
	 * #stepQualificationDiff} (issue #605). */
	public List<ThresholdDecay.Step> simulateSuffixSteps(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end) {
		return simulateSuffix(athlete, field, fromDate, end, decaySettingsFor(athlete), null);
	}

	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate) {
		return resimulateSuffix(athlete, field, fromDate, LocalDate.now(), null, null, true, false, false);
	}

	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end) {
		return resimulateSuffix(athlete, field, fromDate, end, null, null, true, false, false);
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
	 * <p>{@code collectQualificationDiff} (issue #494, recordManualValue's own preview flag)
	 * diffs the MEASURED Steps this call writes against a baseline simulation of the same range
	 * without {@code newManualEntry} - see {@link #stepQualificationDiff} for why a baseline
	 * simulation rather than the stored rows (issue #605 review).
	 *
	 * <p>Runs inside a transaction holding a row lock on {@code athlete} for its duration (design
	 * doc 4.3) - guards against two triggers re-simulating the same field at once. */
	@Transactional
	public ResimulateResult resimulateSuffix(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end,
			ThresholdDecay.ManualEntry newManualEntry, User manualSetBy, boolean rerateActivities, boolean preview,
			boolean collectQualificationDiff) {
		userRepository.findWithLockById(athlete.getId());

		ThresholdDecay.DecaySettings settings = decaySettingsFor(athlete);
		// Baseline: the same range simulated without the new entry (see stepQualificationDiff
		// for why this, not the stored rows).
		List<ThresholdDecay.Step> baselineSteps = collectQualificationDiff
				? simulateSuffix(athlete, field, fromDate, end, settings, null)
				: List.of();
		List<ThresholdDecay.Step> steps = simulateSuffix(athlete, field, fromDate, end, settings, newManualEntry);

		List<ThresholdHistoryService.QualificationDiff> wouldRequalify = collectQualificationDiff
				? stepQualificationDiff(field, baselineSteps, steps)
				: List.of();

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

		return new ResimulateResult(changed, reratedCount, wouldRerate, wouldRequalify);
	}

	/** The ledger slots (keyed by currentFrom) where two simulations of the same range pick a
	 * different measured winner. Comparing two simulations, rather than a simulation against the
	 * stored rows, attributes only what the one input that differs between them changes: stored
	 * rows can already disagree with a fresh simulation for reasons unrelated to the edit being
	 * previewed (e.g. correction rows saved before #605 made the decay-on path honour them), and
	 * those must not show up as this edit's consequences. DECAYED/MANUAL steps are excluded from
	 * both sides - they never carry an activityId, so they're not a "did a different ride
	 * qualify" event. Mirrors the Python backend's step_qualification_diff. */
	public static List<ThresholdHistoryService.QualificationDiff> stepQualificationDiff(
			ThresholdField field, List<ThresholdDecay.Step> before, List<ThresholdDecay.Step> after) {
		Map<LocalDate, ThresholdDecay.Step> beforeByCurrentFrom = measuredByCurrentFrom(before);
		Map<LocalDate, ThresholdDecay.Step> afterByCurrentFrom = measuredByCurrentFrom(after);
		TreeSet<LocalDate> currentFroms = new TreeSet<>();
		currentFroms.addAll(beforeByCurrentFrom.keySet());
		currentFroms.addAll(afterByCurrentFrom.keySet());

		List<ThresholdHistoryService.QualificationDiff> diffs = new ArrayList<>();
		for (LocalDate currentFrom : currentFroms) {
			ThresholdDecay.Step oldStep = beforeByCurrentFrom.get(currentFrom);
			ThresholdDecay.Step newStep = afterByCurrentFrom.get(currentFrom);
			String oldActivityId = oldStep != null ? oldStep.activityId() : null;
			String newActivityId = newStep != null ? newStep.activityId() : null;
			Double oldValue = oldStep != null ? oldStep.value() : null;
			Double newValue = newStep != null ? newStep.value() : null;
			if (!Objects.equals(oldActivityId, newActivityId) || !Objects.equals(oldValue, newValue)) {
				diffs.add(new ThresholdHistoryService.QualificationDiff(
						currentFrom, oldActivityId, newActivityId, wireValue(field, oldValue), wireValue(field, newValue)));
			}
		}
		return diffs;
	}

	private static Map<LocalDate, ThresholdDecay.Step> measuredByCurrentFrom(List<ThresholdDecay.Step> steps) {
		return steps.stream()
				.filter(s -> "measured".equals(s.kind()))
				.collect(Collectors.toMap(ThresholdDecay.Step::currentFrom, s -> s, (a, b) -> a));
	}

	private static Object wireValue(ThresholdField field, Double value) {
		if (value == null) {
			return null;
		}
		return field == ThresholdField.THRESHOLD_PACE ? secondsToMmss(value) : (int) Math.round(value);
	}

	public record PreviewResult(List<ThresholdDecay.Step> steps, ThresholdDecay.DecaySettings settingsUsed) {
	}

	/** Issue #487: resimulateSuffix's own read-only half, stopped before its write block (no
	 * ThresholdHistory delete/save, no athlete save, no re-rating) - gathers the exact same
	 * inputs and calls simulate(), but never touches the ledger, so a caller can try different
	 * tauDays/minPct/maxPct against real history with zero side effects. Returns the Steps
	 * alongside the DecaySettings actually used, so a caller overriding only one of the three can
	 * see what the other two defaulted to (the athlete's own saved settings).
	 *
	 * <p>{@code enabled} is forced true regardless of {@code athlete.isThresholdDecayEnabled()} -
	 * simulate() takes a different, legacy ("disabled") branch when enabled is false that ignores
	 * tauDays/minPct/maxPct entirely, which would silently make every override here a no-op for
	 * an athlete who hasn't turned decay on yet. windowDays/sanityPct are never overridden - the
	 * issue's own signature doesn't offer that, and gatherCandidates' own window scan already
	 * reads athlete.getThresholdWindowDays() directly, so overriding settings.windowDays() alone
	 * wouldn't even change what candidates are gathered. */
	public PreviewResult gatherPreviewSteps(User athlete, ThresholdField field, LocalDate fromDate, LocalDate end,
			Double tauDays, Double minPct, Double maxPct) {
		ThresholdDecay.DecaySettings base = decaySettingsFor(athlete);
		ThresholdDecay.DecaySettings settings = new ThresholdDecay.DecaySettings(base.windowDays(), base.sanityPct(),
				tauDays != null ? tauDays : base.tauDays(), minPct != null ? minPct : base.minPct(),
				maxPct != null ? maxPct : base.maxPct(), true);
		return new PreviewResult(simulateSuffix(athlete, field, fromDate, end, settings, null), settings);
	}

	/** issue #485: anchorValue/anchorEffectiveFrom/decayingSince/target for a currently-decaying
	 * field - null otherwise. Keyed off the CURRENT row's own kind (checked by the caller,
	 * ThresholdHistoryService.summaryFor), not the athlete's live thresholdDecayEnabled flag, so
	 * a now-disabled athlete with a leftover decayed row from when decay was on still gets a
	 * correctly-computed object. Mirrors Django's views._decay_object. */
	public ThresholdSummaryEntry.Decay decayObjectFor(User athlete, ThresholdField field) {
		ThresholdHistory anchor = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseAndKindNotOrderByEffectiveFromDescIdDesc(
						athlete.getId(), field, ThresholdHistoryKind.DECAYED)
				.orElse(null);
		if (anchor == null) {
			// Shouldn't happen if current.getKind() == DECAYED (a decayed row can only exist
			// after a non-decayed anchor was resimulated from), but guard it anyway rather than
			// throwing.
			return null;
		}
		double anchorValue = rowValue(field, anchor);
		ThresholdDecay.DecaySettings settings = decaySettingsFor(athlete);
		LocalDate today = LocalDate.now();
		LocalDate decayingSince = anchor.getEffectiveFrom().plusDays(athlete.getThresholdWindowDays() + 1L);
		Map<LocalDate, Double> loadByDay = decayLoadService.loadByDayForAthlete(athlete, field, anchorValue,
				athlete.getLthr() != null ? athlete.getLthr().doubleValue() : null,
				anchor.getEffectiveFrom().minusDays(settings.windowDays()), today);
		double ratio = ThresholdDecay.decayLoadRatio(loadByDay, anchor.getEffectiveFrom(), today);
		ThresholdHistoryCalculator.Candidate successor = calculator.currentWindowValue(athlete, field, today);
		Double successorValue = successor != null ? successor.impliedValue() : null;
		double target = ThresholdDecay.decayTarget(field, anchorValue, successorValue, ratio, settings);
		Object anchorDisplay = field == ThresholdField.THRESHOLD_PACE ? secondsToMmss(anchorValue) : (int) Math.round(anchorValue);
		Object targetDisplay = field == ThresholdField.THRESHOLD_PACE ? secondsToMmss(target) : (int) Math.round(target);
		return new ThresholdSummaryEntry.Decay(anchorDisplay, anchor.getEffectiveFrom(), decayingSince, targetDisplay);
	}
}
