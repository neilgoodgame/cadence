package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.athletes.ThresholdHistoryCalculator.Candidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.ThresholdHistoryEntry;
import com.cadence.api.athletes.dto.ThresholdHistoryEntryResponse;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wires {@link ThresholdHistoryCalculator}'s pure algorithm to persistence: the ingest hook
 * (recomputeForActivity), the manual on-demand refresh (refreshField), the staleness check
 * (isStale - a cheap date comparison, never a recompute), and the bulk "rebuild from oldest" tool
 * (rebuildHistory). No background polling anywhere - a new activity being created is the only
 * automatic trigger, matching the plan's "no periodic job" decision.
 */
@Service
public class ThresholdHistoryService {

	/** One activity's before/after intensity+tss from a (real or previewed) re-rate - issue #493. */
	public record RerateDiff(
			String activityId, LocalDate date, Double oldIntensity, Double newIntensity, Integer oldTss, Integer newTss) {
	}

	/** Outcome of {@link #recordManualValue} - issue #493. {@code becameCurrent} is false exactly
	 * when {@code isCorrectionOnly} is true (see that method's Javadoc for the two reasons why).
	 * {@code reratedCount} is 0 during a preview (nothing is persisted); {@code wouldRerate} is
	 * only populated during a preview. */
	public record ManualEditResult(
			boolean changed, boolean becameCurrent, boolean isCorrectionOnly, int reratedCount, List<RerateDiff> wouldRerate) {
		public ManualEditResult(boolean changed, boolean becameCurrent, boolean isCorrectionOnly) {
			this(changed, becameCurrent, isCorrectionOnly, 0, List.of());
		}
	}

	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ThresholdHistoryCalculator calculator;
	private final ActivityRepository activityRepository;
	private final UserRepository userRepository;
	private final TssRecomputeService tssRecomputeService;

	public ThresholdHistoryService(ThresholdHistoryRepository thresholdHistoryRepository,
			ThresholdHistoryCalculator calculator, ActivityRepository activityRepository, UserRepository userRepository,
			TssRecomputeService tssRecomputeService) {
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.calculator = calculator;
		this.activityRepository = activityRepository;
		this.userRepository = userRepository;
		this.tssRecomputeService = tssRecomputeService;
	}

	// FTP -> bike; critical running power/threshold pace -> run - the sport each field's
	// ledger entry should re-rate activities for after a manual edit (part 3 of issue #481's fix).
	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	private static LocalDate dateOf(Activity activity) {
		return activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
	}

	// A local copy, not a cross-class import - matches this codebase's existing convention (see
	// ThresholdHistoryCalculator's own mmssToSeconds).
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

	private static String secondsToMmss(double seconds) {
		int total = (int) Math.round(seconds);
		return "%d:%02d".formatted(total / 60, total % 60);
	}

	/** Checks whether `activity`'s own effort changes the athlete's current window value for
	 * each field relevant to its sport (bike -> ftp; run -> criticalRunPower and thresholdPace),
	 * recording a new ThresholdHistory entry (and updating the athlete's cached profile value) if
	 * so. Called at ingest/import time - see ThresholdHistoryTasklet and ImportReader. A no-op
	 * for every other sport. */
	@Transactional
	public void recomputeForActivity(Activity activity) {
		User athlete = activity.getAthlete();
		LocalDate asOf = dateOf(activity);
		for (ThresholdField field : ThresholdField.values()) {
			if (fieldAppliesTo(field, activity)) {
				recomputeAndRecord(athlete, field, asOf);
			}
		}
	}

	private static boolean fieldAppliesTo(ThresholdField field, Activity activity) {
		return switch (field) {
			case FTP -> activity.getSport() == com.cadence.api.common.domain.Sport.BIKE;
			case CRITICAL_RUN_POWER, THRESHOLD_PACE -> activity.getSport() == com.cadence.api.common.domain.Sport.RUN;
		};
	}

	/** Manual on-demand recompute (the dashboard's "this value is stale - refresh now" action) -
	 * the same cheap current-window computation as the ingest hook, just athlete-triggered rather
	 * than tied to a specific new activity. Returns whether the value actually changed. */
	@Transactional
	public boolean refreshField(User athlete, ThresholdField field) {
		return recomputeAndRecord(athlete, field, null);
	}

	/** Self-edit shorthand (setBy = athlete), today, re-rating on - see the full overload for
	 * backdating/opt-out/preview (issue #493). */
	@Transactional
	public ManualEditResult recordManualValue(User athlete, ThresholdField field, Integer valueNumeric, String valuePace) {
		return recordManualValue(athlete, field, valueNumeric, valuePace, athlete, null, true, false);
	}

	/** As the 4-arg overload, with an explicit `setBy` (a coach-on-behalf-of edit) - today,
	 * re-rating on. */
	@Transactional
	public ManualEditResult recordManualValue(User athlete, ThresholdField field, Integer valueNumeric, String valuePace, User setBy) {
		return recordManualValue(athlete, field, valueNumeric, valuePace, setBy, null, true, false);
	}

	/** The athlete directly declared this value via their profile (PATCH /v1/athletes/{id}) -
	 * trusted unconditionally (no sanity-band check, no window search: an explicit choice, not a
	 * computed candidate). Behaves exactly like any other ledger entry from here on: ages out
	 * after thresholdWindowDays like any other, and can be superseded by a later qualifying
	 * activity or another manual edit. A no-op if the submitted value already matches what's in
	 * effect at `effectiveFrom` (e.g. re-saving the profile form without touching this field -
	 * the frontend always resubmits every field). Owns the athlete's cached field entirely (issue
	 * #493) - never written by the caller; see the "became current" logic below for why.
	 *
	 * <p><code>effectiveFrom</code> (issue #493, null = today) lets the edit be backdated instead
	 * of always applying from today. The row is always written - it's a legitimate historical
	 * correction either way - but it only becomes the live/current value ({@code becameCurrent})
	 * when BOTH (a) no existing row has a later effectiveFrom, and (b) it isn't already outside
	 * thresholdWindowDays as of today (i.e. it wouldn't be stale the moment it's saved).
	 * Otherwise it's {@code correctionOnly}: still used by any activity-scoped lookup
	 * (ZoneService.referenceFor, filtered on currentFrom), just excluded from every "what's
	 * current" lookup. Throws ValidationException for a future date or a date before the
	 * athlete's first activity of this field's sport - a date outside the window is allowed
	 * (that's exactly what makes it correction-only, not an error).
	 *
	 * <p>{@code rerateActivities} (default true, matching this method's pre-#493 unconditional
	 * behavior) re-rates (intensity + tss) every activity of the affected sport on/after
	 * `effectiveFrom` - see {@link #reRateActivitiesFrom}. {@code preview} runs the whole write
	 * (ledger row, live-field update, re-rate) against the real code path and then rolls it
	 * back, so the returned diff is guaranteed identical to a real run's, with nothing persisted.
	 *
	 * <p>`setBy` is the acting principal - equal to `athlete` for a self-edit, a different User
	 * for a coach acting on the athlete's behalf. */
	@Transactional
	public ManualEditResult recordManualValue(User athlete, ThresholdField field, Integer valueNumeric, String valuePace,
			User setBy, LocalDate effectiveFrom, boolean rerateActivities, boolean preview) {
		Double implied = field == ThresholdField.THRESHOLD_PACE
				? mmssToSeconds(valuePace)
				: (valueNumeric != null ? valueNumeric.doubleValue() : null);
		if (implied == null || implied == 0) {
			return new ManualEditResult(false, false, false);
		}

		LocalDate today = LocalDate.now();
		LocalDate givenDate = effectiveFrom != null ? effectiveFrom : today;
		if (givenDate.isAfter(today)) {
			throw new ValidationException("effectiveFrom must not be in the future.", "effectiveFrom");
		}

		LocalDate firstActivityDate = activityRepository
				.findEarliestStartDateForSport(athlete.getId(), sportFor(field))
				.map(i -> i.atZone(ZoneOffset.UTC).toLocalDate())
				.orElse(null);
		if (firstActivityDate != null && givenDate.isBefore(firstActivityDate)) {
			throw new ValidationException(
					"effectiveFrom must not be before the athlete's first activity of this sport.", "effectiveFrom");
		}

		Double existingValue = valueInEffectAt(athlete, field, givenDate);
		if (Objects.equals(existingValue, implied)) {
			return new ManualEditResult(false, false, false);
		}

		boolean hasLaterRow =
				thresholdHistoryRepository.existsByAthleteIdAndFieldAndEffectiveFromGreaterThan(athlete.getId(), field, givenDate);
		boolean staleOnArrival = ChronoUnit.DAYS.between(givenDate, today) > athlete.getThresholdWindowDays();
		boolean correctionOnly = hasLaterRow || staleOnArrival;
		boolean becameCurrent = !correctionOnly;

		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setSourceActivity(null);
		entry.setSetBy(setBy);
		entry.setEffectiveFrom(givenDate);
		entry.setCurrentFrom(givenDate);
		entry.setKind(ThresholdHistoryKind.MANUAL);
		entry.setCorrectionOnly(correctionOnly);
		if (field == ThresholdField.THRESHOLD_PACE) {
			entry.setValuePace(valuePace);
		}
		else {
			entry.setValueNumeric(valueNumeric);
		}
		thresholdHistoryRepository.save(entry);

		RerateResult rerateResult = reRateActivitiesFrom(athlete, field, givenDate, rerateActivities, preview);

		// Never touched by the caller (unlike pre-#493) - becameCurrent already accounts for
		// whether this edit is actually the one that should be live, so re-deriving "the" current
		// row here is always right, including a no-op-looking re-derivation when it isn't this
		// edit's own row that's current.
		if (!preview) {
			ThresholdHistory current = thresholdHistoryRepository
					.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
					.orElse(null);
			switch (field) {
				case FTP -> athlete.setFtp(current != null ? current.getValueNumeric() : null);
				case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower(current != null ? current.getValueNumeric() : null);
				case THRESHOLD_PACE -> athlete.setThresholdPace(current != null ? current.getValuePace() : null);
			}
			userRepository.save(athlete);
		}

		// preview's actual rollback happens once, at the end of the whole request - see
		// AthleteService.updateProfile/PreviewRollbackSignal. This method's own @Transactional
		// only ever participates in that outer transaction (Spring's default REQUIRED
		// propagation), so marking rollback-only here - rather than there - would mark the
		// shared transaction while this call still returns normally; the owning (real, new)
		// transaction would then throw UnexpectedRollbackException on its own commit instead of
		// returning anything, for every multi-field preview too (the first field's own commit
		// couldn't be deferred until the others ran).

		return new ManualEditResult(true, becameCurrent, correctionOnly, rerateResult.reratedCount(), rerateResult.diffs());
	}

	// The ledger value already in effect on `asOf` - the same currentFrom <= date lookup
	// ZoneService.referenceFor uses for an activity's own date, just keyed on an arbitrary date
	// instead of an activity's. Unlike the correction-excluding "what's current today" lookup,
	// this deliberately does NOT exclude correction-only rows - it's used to decide whether a
	// backdated edit is a no-op (does it match what a ride on that date would already be rated
	// against), which is exactly what a correction-only row's currentFrom already governs.
	private Double valueInEffectAt(User athlete, ThresholdField field, LocalDate asOf) {
		return thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCurrentFromLessThanEqualOrderByCurrentFromDescIdDesc(athlete.getId(), field, asOf)
				.map(entry -> field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(entry.getValuePace()) : entry.getValueNumeric().doubleValue())
				.orElse(null);
	}

	private record RerateResult(int reratedCount, List<RerateDiff> diffs) {
	}

	/** Re-rates (intensity + tss) every one of the athlete's activities of the affected sport
	 * dated on/after a manual threshold edit's effective date - otherwise they'd stay scored
	 * against the now-superseded value until someone happens to press a recompute button (issue
	 * #481, part 3). No upper bound needed, even once effectiveFrom can be backdated (issue
	 * #493): each activity's own referenceFor lookup (filtered on currentFrom) already stops
	 * applying this edit once a later ledger row's currentFrom takes over for that date, so an
	 * activity past that boundary just re-rates to the value it already had - rate() only
	 * reports a change when something actually did.
	 *
	 * <p>`persist` actually saves a changed activity (the real rerateActivities=true path).
	 * `collectDiff` returns a RerateDiff per changed activity regardless of `persist` (issue
	 * #493's preview, which wants to know what WOULD change even when the box isn't ticked). */
	private RerateResult reRateActivitiesFrom(
			User athlete, ThresholdField field, LocalDate effectiveFrom, boolean persist, boolean collectDiff) {
		if (!persist && !collectDiff) {
			return new RerateResult(0, List.of());
		}
		Instant start = effectiveFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Activity> activities =
				activityRepository.findForReRateAfterThresholdEdit(athlete.getId(), sportFor(field), start);
		int reratedCount = 0;
		List<RerateDiff> diffs = new ArrayList<>();
		for (Activity activity : activities) {
			Double oldIntensity = activity.getIntensity();
			int oldTss = activity.getTss();
			if (!tssRecomputeService.rate(activity, athlete)) {
				continue;
			}
			if (collectDiff) {
				diffs.add(new RerateDiff(activity.getId(), dateOf(activity), oldIntensity, activity.getIntensity(), oldTss, activity.getTss()));
			}
			if (persist) {
				activityRepository.save(activity);
				reratedCount++;
			}
		}
		return new RerateResult(reratedCount, diffs);
	}

	private boolean recomputeAndRecord(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		Candidate candidate = calculator.currentWindowValue(athlete, field, effectiveAsOf);
		if (candidate == null) {
			return false;
		}
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		// "Current" (summaryFor/ledgerFor/isStale) is whichever row has the latest effective_from,
		// not whichever was inserted most recently - so a candidate dated *before* the existing
		// current entry could never actually become current, however different its value. Recording
		// it anyway is worse than a no-op: it's a dead row that silently re-inserts itself (with a
		// new id) every time the ingest hook or a manual refresh re-evaluates the same activity,
		// since its value keeps comparing as "different" from the still-unbeaten current entry.
		// Seen for real: a stale manually-entered FTP (255W, no source activity) permanently
		// outranked a genuine 225W ride-based candidate dated three weeks earlier, and every
		// re-trigger added another identical dead 225W row instead of just doing nothing.
		if (latest != null && candidate.date().isBefore(latest.getEffectiveFrom())) {
			return false;
		}
		Double latestValue = latest == null ? null
				: field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(latest.getValuePace()) : latest.getValueNumeric().doubleValue();
		if (Objects.equals(latestValue, candidate.impliedValue())) {
			return false;
		}
		recordCandidate(athlete, field, candidate, effectiveAsOf);
		return true;
	}

	// Package-visible, not private - ThresholdSuggestionService.accept() reuses this exact
	// persist-and-cache-the-profile-value path for an accepted suggestion, same as any other
	// candidate.
	void recordCandidate(User athlete, ThresholdField field, Candidate candidate, LocalDate currentFrom) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setSourceActivity(activityRepository.getReferenceById(candidate.activityId()));
		entry.setEffectiveFrom(candidate.date());
		entry.setCurrentFrom(currentFrom);
		entry.setKind(ThresholdHistoryKind.MEASURED);
		if (field == ThresholdField.THRESHOLD_PACE) {
			entry.setValuePace(secondsToMmss(candidate.impliedValue()));
		}
		else {
			entry.setValueNumeric((int) Math.round(candidate.impliedValue()));
		}
		thresholdHistoryRepository.save(entry);

		switch (field) {
			case FTP -> athlete.setFtp((int) Math.round(candidate.impliedValue()));
			case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower((int) Math.round(candidate.impliedValue()));
			case THRESHOLD_PACE -> athlete.setThresholdPace(secondsToMmss(candidate.impliedValue()));
		}
		userRepository.save(athlete);
	}

	/** Whether the current entry's source activity has aged out of the trailing window - a plain
	 * date comparison, not a recompute, so it's cheap enough to call on every read (e.g. the
	 * dashboard). True with no entry at all (nothing to be current). */
	public boolean isStale(User athlete, ThresholdField field, LocalDate asOf) {
		LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		if (latest == null) {
			return true;
		}
		return ChronoUnit.DAYS.between(latest.getEffectiveFrom(), effectiveAsOf) > athlete.getThresholdWindowDays();
	}

	/** The bulk "recompute history from oldest" tool - replaces the *measured* portion of the
	 * ledger for one field with a fresh replay, then syncs the athlete's cached profile value to
	 * whatever is now current (or clears it if there's no qualifying row at all). A manual or
	 * correction-only row is never deleted or rederived here - it isn't something activities can
	 * reconstruct, so wiping it on every rebuild would silently discard a real, intentional
	 * correction (issue #504; #493 requires the opposite - "rebuild must keep backdated manual
	 * rows"). calculator.replayFullHistory already merges those surviving rows back into the
	 * window at their own effectiveFrom, so measured activities are still correctly suppressed/
	 * revealed relative to them - see its own Javadoc. Calls onProgress(current, total) after
	 * each activity considered, for the SSE bulk-rebuild endpoint. Returns the final entry
	 * count (measured rows written, not the ledger's total size). */
	@Transactional
	public int rebuildHistory(User athlete, ThresholdField field, BiConsumer<Integer, Integer> onProgress) {
		thresholdHistoryRepository.deleteByAthleteIdAndFieldAndKind(athlete.getId(), field, ThresholdHistoryKind.MEASURED);
		List<ThresholdHistoryEntry> entries = calculator.replayFullHistory(athlete, field, onProgress);
		for (ThresholdHistoryEntry entry : entries) {
			ThresholdHistory row = new ThresholdHistory();
			row.setAthlete(athlete);
			row.setField(field);
			row.setSourceActivity(activityRepository.getReferenceById(entry.activityId()));
			row.setEffectiveFrom(entry.effectiveFrom());
			row.setCurrentFrom(entry.currentFrom());
			row.setKind(ThresholdHistoryKind.MEASURED);
			if (field == ThresholdField.THRESHOLD_PACE) {
				row.setValuePace(secondsToMmss(entry.value()));
			}
			else {
				row.setValueNumeric((int) Math.round(entry.value()));
			}
			thresholdHistoryRepository.save(row);
		}

		// The current row after the rebuild, not "the replay's last entry" - a still-valid
		// manual row (kept, not deleted above) can be more recent than any measured row the
		// replay just wrote, and correctionOnlyFalse already excludes a correction-only row from
		// ever being mistaken for current (same lookup manualAnchorCandidate/isStale/summaryFor
		// use).
		ThresholdHistory current = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
				.orElse(null);
		switch (field) {
			case FTP -> athlete.setFtp(current != null ? current.getValueNumeric() : null);
			case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower(current != null ? current.getValueNumeric() : null);
			case THRESHOLD_PACE -> athlete.setThresholdPace(current != null ? current.getValuePace() : "");
		}
		userRepository.save(athlete);
		return entries.size();
	}

	private static Object valueOf(ThresholdField field, ThresholdHistory entry) {
		return field == ThresholdField.THRESHOLD_PACE ? entry.getValuePace() : entry.getValueNumeric();
	}

	// Null for a manually-entered value (see recordManualValue) - not sourced from any activity.
	private static String sourceActivityIdOf(ThresholdHistory entry) {
		return entry.getSourceActivity() != null ? entry.getSourceActivity().getId() : null;
	}

	// Null for an auto-derived value (sourceActivity populated instead) - see recordManualValue.
	private static String setByUserIdOf(ThresholdHistory entry) {
		return entry.getSetBy() != null ? entry.getSetBy().getId() : null;
	}

	/** GET /v1/athletes/{id}/thresholds' per-field entry: current + previous value, and whether
	 * the current one has aged out of the window. */
	public ThresholdSummaryEntry summaryFor(User athlete, ThresholdField field) {
		// Excludes correction-only rows (issue #493 - a backdated manual edit that never
		// actually took over) from both current and previous.
		List<ThresholdHistory> entries = thresholdHistoryRepository
				.findByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(athlete.getId(), field);
		if (entries.isEmpty()) {
			return new ThresholdSummaryEntry(null, null, null, null, true, null, null);
		}
		ThresholdHistory current = entries.get(0);
		ThresholdHistory previous = entries.size() > 1 ? entries.get(1) : null;
		boolean stale = ChronoUnit.DAYS.between(current.getEffectiveFrom(), LocalDate.now()) > athlete.getThresholdWindowDays();
		return new ThresholdSummaryEntry(valueOf(field, current), previous != null ? valueOf(field, previous) : null,
				sourceActivityIdOf(current), current.getEffectiveFrom(), stale, setByUserIdOf(current), current.getKind());
	}

	/** GET /v1/athletes/{id}/threshold-history?field=... - the full ledger, most recent first. */
	public List<ThresholdHistoryEntryResponse> ledgerFor(User athlete, ThresholdField field) {
		return thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).stream()
				.map(entry -> new ThresholdHistoryEntryResponse(valueOf(field, entry), sourceActivityIdOf(entry),
						entry.getEffectiveFrom(), entry.getCurrentFrom(), setByUserIdOf(entry), entry.getKind(), entry.isCorrectionOnly(),
						entry.getCreatedAt()))
				.toList();
	}

	/** As {@link #ledgerFor}, but optionally restricted to entries whose effectiveFrom falls
	 * within [from, to] - either bound may be null. Backs the list_threshold_history MCP tool. */
	public List<ThresholdHistoryEntryResponse> ledgerFor(User athlete, ThresholdField field, LocalDate from, LocalDate to) {
		return thresholdHistoryRepository.findByAthleteIdAndFieldInEffectiveFromRange(athlete.getId(), field, from, to).stream()
				.map(entry -> new ThresholdHistoryEntryResponse(valueOf(field, entry), sourceActivityIdOf(entry),
						entry.getEffectiveFrom(), entry.getCurrentFrom(), setByUserIdOf(entry), entry.getKind(), entry.isCorrectionOnly(),
						entry.getCreatedAt()))
				.toList();
	}
}
