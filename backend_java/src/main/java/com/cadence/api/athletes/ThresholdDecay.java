package com.cadence.api.athletes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pure day-by-day threshold decay simulation (issue #485, slice 1 of the threshold-decay plan at
 * docs/plans/threshold-decay.md). Gives a threshold value a gradual decay curve once its
 * anchoring evidence ages out of the trailing window, instead of the cliff
 * {@link ThresholdHistoryCalculator#currentWindowValue}/{@link ThresholdHistoryCalculator#replayFullHistory}
 * produce today when a better-but-now-stale candidate simply falls out of the window and the
 * next-best one (however much lower) takes over immediately.
 *
 * <p>Pure/read-only, no JPA - unit-testable with no database, static and final like {@link
 * com.cadence.api.activities.calc.TssCalculator}. Everything here operates on plain inputs the
 * caller already has; wiring this into ingest/manual-edit/refresh/rebuild, the intensity
 * histogram that would produce {@code loadByDay} for real, and the daily cron job that would call
 * this for every athlete are all deferred to a follow-up slice.
 *
 * <p>All arithmetic happens in <b>effort units</b> ({@code e = 1/value} for {@code
 * THRESHOLD_PACE}, since a lower pace is the improvement there; {@code e = value} for every other
 * field, where higher is the improvement) rather than native units, so the single recurrence below
 * doesn't need a field-specific branch: "higher effort is always better" holds uniformly, including
 * for the {@code x >= round(v)} upward-reset test and the {@code min(v, ...)} "never eases upward"
 * clamp. Native units (watts, or whole seconds/km for pace) are only ever used at the boundary -
 * inputs and {@link Step#value()}.
 *
 * <p><b>The start/seed contract</b>: {@code start} must be no later than {@code seedAnchorDate +
 * settings.windowDays() + 1} (the anchor's own expiry) - i.e. {@code v} has not yet started
 * decaying away from the seed anchor's value by the time {@code start} is reached. In practice
 * every caller passes {@code start == seedAnchorDate} (resuming right at the anchor that's
 * already on file) or an earlier date (a cold start with no prior anchor, {@code
 * seedAnchorValue == null}). {@code candidates}/{@code manualEntries} must NOT include whatever
 * activity/edit already produced the seed anchor itself - only genuinely new evidence the caller
 * hasn't already folded into the seed. They should, however, include anything from up to {@code
 * settings.windowDays()} before {@code start} that's still within the trailing window, so the day
 * loop's own sliding-window successor tracking (see {@link DecayCandidate}) starts correctly
 * populated rather than empty.
 */
public final class ThresholdDecay {

	public static final String MEASURED = "measured";
	public static final String MANUAL = "manual";
	public static final String DECAYED = "decayed";

	public record DecaySettings(
			int windowDays, int sanityPct, double tauDays, double minPct, double maxPct, boolean enabled) {
	}

	/** A qualifying activity's implied value for {@code field} on {@code date} - native units,
	 * already rounded (as {@code ThresholdHistoryCalculator}'s own impliedValueDetail already
	 * does). {@code accepted} bypasses the sanity band, mirroring {@code
	 * AcceptedThresholdCandidate}. */
	public record DecayCandidate(LocalDate date, double impliedValue, String activityId, boolean accepted) {
		public DecayCandidate(LocalDate date, double impliedValue, String activityId) {
			this(date, impliedValue, activityId, false);
		}
	}

	/** An athlete- or coach-entered value, native units - always becomes the anchor on its own
	 * date regardless of whether it's higher or lower than the current value (unlike a measured
	 * candidate's upward-reset test), matching the existing recordManualValue rule. */
	public record ManualEntry(LocalDate date, double value, String activityId) {
		public ManualEntry(LocalDate date, double value) {
			this(date, value, null);
		}
	}

	/** One ledger row {@link #simulate} would write - native units, already rounded. {@code kind}
	 * is one of {@link #MEASURED}/{@link #MANUAL}/{@link #DECAYED}. */
	public record Step(double value, String kind, String activityId, LocalDate effectiveFrom, LocalDate currentFrom) {
	}

	// Pace is seconds/km - a lower value is the improvement, the opposite of every other field
	// (same distinction ThresholdHistoryCalculator's own LOWER_IS_BETTER draws) - but here it only
	// ever matters for the effort-unit conversion, not as a separate comparison branch (see class
	// Javadoc).
	private static final ThresholdField PACE_FIELD = ThresholdField.THRESHOLD_PACE;

	private static double toEffort(ThresholdField field, double value) {
		return field == PACE_FIELD ? 1.0 / value : value;
	}

	private static double fromEffort(ThresholdField field, double effort) {
		return field == PACE_FIELD ? 1.0 / effort : effort;
	}

	private static double roundNative(double value) {
		return Math.round(value);
	}

	/** Same rule as {@code ThresholdHistoryCalculator.withinSanityBand} - always true with no
	 * reference yet. */
	private static boolean withinSanityBand(double candidateValue, Double referenceValue, int sanityPct) {
		if (referenceValue == null || referenceValue == 0) {
			return true;
		}
		double deviation = Math.abs(candidateValue - referenceValue) / referenceValue;
		return deviation <= sanityPct / 100.0;
	}

	private static Map<LocalDate, List<DecayCandidate>> groupByDay(List<DecayCandidate> candidates) {
		Map<LocalDate, List<DecayCandidate>> byDay = new TreeMap<>();
		for (DecayCandidate c : candidates) {
			byDay.computeIfAbsent(c.date(), k -> new ArrayList<>()).add(c);
		}
		return byDay;
	}

	private static Map<LocalDate, ManualEntry> indexByDay(List<ManualEntry> entries) {
		Map<LocalDate, ManualEntry> byDay = new HashMap<>();
		for (ManualEntry m : entries) {
			byDay.put(m.date(), m);
		}
		return byDay;
	}

	/** The one entry point - see the class Javadoc for the effort-unit design and the start/seed
	 * contract. {@code seedAnchorValue}/{@code seedAnchorDate}/{@code seedAnchorKind} describe the
	 * anchor already on file as of just before {@code start} (all null for a cold start with no
	 * prior anchor at all). */
	public static List<Step> simulate(
			ThresholdField field, DecaySettings settings, Double seedAnchorValue, LocalDate seedAnchorDate,
			String seedAnchorKind, List<DecayCandidate> candidates, List<ManualEntry> manualEntries,
			Map<LocalDate, Double> loadByDay, LocalDate start, LocalDate end) {
		Map<LocalDate, List<DecayCandidate>> candidatesByDay = groupByDay(candidates);
		Map<LocalDate, ManualEntry> manualByDay = indexByDay(manualEntries);
		if (settings.enabled()) {
			return simulateEnabled(
					field, settings, seedAnchorValue, seedAnchorDate, candidatesByDay, manualByDay, loadByDay, start, end);
		}
		return simulateDisabled(
				field, settings, seedAnchorValue, seedAnchorDate, seedAnchorKind, candidatesByDay, manualByDay, start, end);
	}

	private record WindowEntry(LocalDate date, double effort, String activityId) {
	}

	private static List<Step> simulateEnabled(
			ThresholdField field, DecaySettings settings, Double seedAnchorValue, LocalDate seedAnchorDate,
			Map<LocalDate, List<DecayCandidate>> candidatesByDay, Map<LocalDate, ManualEntry> manualByDay,
			Map<LocalDate, Double> loadByDay, LocalDate start, LocalDate end) {
		LocalDate a = seedAnchorDate;
		Double anchorEffort = seedAnchorValue != null ? toEffort(field, seedAnchorValue) : null;
		Double vEffort = anchorEffort;
		// The successor pool: qualifying *activities* only (not manual entries - see 2.1 of the
		// plan doc), sliding-window-maximum pruned exactly like ThresholdHistoryCalculator's own
		// window scan.
		List<WindowEntry> window = new ArrayList<>();
		Double lastEmittedNative = anchorEffort != null ? roundNative(fromEffort(field, anchorEffort)) : null;
		List<Step> steps = new ArrayList<>();

		LocalDate loopStart = seedAnchorDate != null && seedAnchorDate.isBefore(start) ? seedAnchorDate : start;
		for (LocalDate d = loopStart; !d.isAfter(end); d = d.plusDays(1)) {
			String eventKind = null;
			String eventActivityId = null;
			LocalDate eventEffectiveFrom = null;

			ManualEntry manual = manualByDay.get(d);
			if (manual != null) {
				anchorEffort = toEffort(field, manual.value());
				a = d;
				vEffort = anchorEffort;
				eventKind = MANUAL;
				eventActivityId = manual.activityId();
				eventEffectiveFrom = d;
			}

			Double refNative = vEffort != null ? roundNative(fromEffort(field, vEffort)) : null;
			for (DecayCandidate candidate : candidatesByDay.getOrDefault(d, List.of())) {
				if (!candidate.accepted() && refNative != null
						&& !withinSanityBand(candidate.impliedValue(), refNative, settings.sanityPct())) {
					continue;
				}
				double candidateEffort = toEffort(field, candidate.impliedValue());
				window = window.stream().filter(w -> w.effort() >= candidateEffort).collect(Collectors.toCollection(ArrayList::new));
				window.add(new WindowEntry(d, candidateEffort, candidate.activityId()));
				boolean isUpwardReset = vEffort == null || candidateEffort >= toEffort(field, roundNative(fromEffort(field, vEffort)));
				if (isUpwardReset) {
					anchorEffort = candidateEffort;
					a = d;
					vEffort = candidateEffort;
					eventKind = MEASURED;
					eventActivityId = candidate.activityId();
					eventEffectiveFrom = d;
					refNative = roundNative(fromEffort(field, vEffort));
				}
			}

			LocalDate cutoff = d.minusDays(settings.windowDays());
			window = window.stream().filter(w -> w.date().isAfter(cutoff)).collect(Collectors.toCollection(ArrayList::new));

			if (vEffort != null) {
				LocalDate expiry = a.plusDays(settings.windowDays() + 1L);
				if (d.isBefore(expiry)) {
					vEffort = anchorEffort;
				}
				else {
					double loadToday = loadByDay.getOrDefault(d, 0.0);
					double loadAtAnchor = loadByDay.getOrDefault(a, 0.0);
					double ratio = loadAtAnchor < 1 ? 1.0 : Math.min(1.0, Math.max(0.0, loadToday / loadAtAnchor));
					double amplitude = settings.minPct() + (settings.maxPct() - settings.minPct()) * (1 - ratio);
					double floorEffort = anchorEffort * (1 - amplitude / 100.0);
					Double successorEffort = window.stream().map(WindowEntry::effort).max(Comparator.naturalOrder()).orElse(null);
					double targetEffort = successorEffort != null ? Math.max(successorEffort, floorEffort)
							: anchorEffort * (1 - settings.maxPct() / 100.0);
					double decayFactor = Math.exp(-1.0 / settings.tauDays());
					vEffort = Math.min(vEffort, targetEffort + (vEffort - targetEffort) * decayFactor);
				}
			}

			if (vEffort != null && !d.isBefore(start)) {
				double newNative = roundNative(fromEffort(field, vEffort));
				if (eventKind != null) {
					steps.add(new Step(newNative, eventKind, eventActivityId, eventEffectiveFrom, d));
					lastEmittedNative = newNative;
				}
				else if (lastEmittedNative == null || newNative != lastEmittedNative) {
					steps.add(new Step(newNative, DECAYED, null, d, d));
					lastEmittedNative = newNative;
				}
			}
		}

		return steps;
	}

	private record LegacyWindowEntry(LocalDate date, double effort, String kind, String activityId) {
	}

	/** Decay off: reproduces today's behaviour ({@code
	 * ThresholdHistoryCalculator.replayFullHistory}'s sliding-window maximum, generalized to
	 * effort units) - whichever qualifying activity or manual entry is the current window's best
	 * takes over the instant the old one ages out or is beaten, with no decay curve in between.
	 * Unlike enabled mode, manual entries compete in the same window as activities rather than
	 * unconditionally overriding it - there's no decay clock here for an unconditional override to
	 * usefully "reset". */
	private static List<Step> simulateDisabled(
			ThresholdField field, DecaySettings settings, Double seedAnchorValue, LocalDate seedAnchorDate,
			String seedAnchorKind, Map<LocalDate, List<DecayCandidate>> candidatesByDay,
			Map<LocalDate, ManualEntry> manualByDay, LocalDate start, LocalDate end) {
		List<LegacyWindowEntry> window = new ArrayList<>();
		if (seedAnchorValue != null) {
			window.add(new LegacyWindowEntry(seedAnchorDate, toEffort(field, seedAnchorValue), seedAnchorKind, null));
		}
		Double lastEmittedNative = window.isEmpty() ? null : roundNative(fromEffort(field, window.get(0).effort()));
		List<Step> steps = new ArrayList<>();

		LocalDate loopStart = seedAnchorDate != null && seedAnchorDate.isBefore(start) ? seedAnchorDate : start;
		for (LocalDate d = loopStart; !d.isAfter(end); d = d.plusDays(1)) {
			ManualEntry manual = manualByDay.get(d);
			if (manual != null) {
				double manualEffort = toEffort(field, manual.value());
				window = window.stream().filter(w -> w.effort() >= manualEffort).collect(Collectors.toCollection(ArrayList::new));
				window.add(new LegacyWindowEntry(d, manualEffort, MANUAL, null));
			}

			Double refNative = window.isEmpty() ? null : roundNative(fromEffort(field, window.get(0).effort()));
			for (DecayCandidate candidate : candidatesByDay.getOrDefault(d, List.of())) {
				if (!candidate.accepted() && refNative != null
						&& !withinSanityBand(candidate.impliedValue(), refNative, settings.sanityPct())) {
					continue;
				}
				double candidateEffort = toEffort(field, candidate.impliedValue());
				window = window.stream().filter(w -> w.effort() >= candidateEffort).collect(Collectors.toCollection(ArrayList::new));
				window.add(new LegacyWindowEntry(d, candidateEffort, MEASURED, candidate.activityId()));
				refNative = roundNative(fromEffort(field, window.get(0).effort()));
			}

			LocalDate cutoff = d.minusDays(settings.windowDays());
			window = window.stream().filter(w -> w.date().isAfter(cutoff)).collect(Collectors.toCollection(ArrayList::new));

			if (!d.isBefore(start)) {
				if (!window.isEmpty()) {
					LegacyWindowEntry best = window.get(0);
					double newNative = roundNative(fromEffort(field, best.effort()));
					if (lastEmittedNative == null || newNative != lastEmittedNative) {
						steps.add(new Step(newNative, best.kind(), best.activityId(), best.date(), d));
						lastEmittedNative = newNative;
					}
				}
				else {
					lastEmittedNative = null;
				}
			}
		}

		return steps;
	}

	private ThresholdDecay() {
	}
}
