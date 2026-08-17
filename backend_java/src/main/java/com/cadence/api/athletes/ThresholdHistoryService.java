package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ThresholdHistoryCalculator.Candidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.ThresholdHistoryEntry;
import com.cadence.api.athletes.dto.ThresholdHistoryEntryResponse;
import com.cadence.api.athletes.dto.ThresholdSummaryEntry;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ThresholdHistoryCalculator calculator;
	private final ActivityRepository activityRepository;
	private final UserRepository userRepository;

	public ThresholdHistoryService(ThresholdHistoryRepository thresholdHistoryRepository,
			ThresholdHistoryCalculator calculator, ActivityRepository activityRepository, UserRepository userRepository) {
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.calculator = calculator;
		this.activityRepository = activityRepository;
		this.userRepository = userRepository;
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

	/** The athlete directly declared this value via their profile (PATCH /v1/athletes/{id}) -
	 * trusted unconditionally (no sanity-band check, no window search: an explicit choice, not a
	 * computed candidate), effective from today. Behaves exactly like any other ledger entry from
	 * here on: ages out after thresholdWindowDays like any other, and can be superseded by a
	 * later qualifying activity or another manual edit. A no-op if the submitted value matches
	 * what's already current (e.g. re-saving the profile form without touching this field - the
	 * frontend always resubmits every field). Doesn't touch the athlete's live field itself - the
	 * caller (AthleteService.updateProfile) already wrote that as part of the same request. */
	@Transactional
	public boolean recordManualValue(User athlete, ThresholdField field, Integer valueNumeric, String valuePace) {
		Double implied = field == ThresholdField.THRESHOLD_PACE
				? mmssToSeconds(valuePace)
				: (valueNumeric != null ? valueNumeric.doubleValue() : null);
		if (implied == null || implied == 0) {
			return false;
		}
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		Double latestValue = latest == null ? null
				: field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(latest.getValuePace()) : latest.getValueNumeric().doubleValue();
		if (Objects.equals(latestValue, implied)) {
			return false;
		}
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setSourceActivity(null);
		entry.setEffectiveFrom(LocalDate.now());
		if (field == ThresholdField.THRESHOLD_PACE) {
			entry.setValuePace(valuePace);
		}
		else {
			entry.setValueNumeric(valueNumeric);
		}
		thresholdHistoryRepository.save(entry);
		return true;
	}

	private boolean recomputeAndRecord(User athlete, ThresholdField field, LocalDate asOf) {
		Candidate candidate = calculator.currentWindowValue(athlete, field, asOf);
		if (candidate == null) {
			return false;
		}
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		Double latestValue = latest == null ? null
				: field == ThresholdField.THRESHOLD_PACE ? mmssToSeconds(latest.getValuePace()) : latest.getValueNumeric().doubleValue();
		if (Objects.equals(latestValue, candidate.impliedValue())) {
			return false;
		}
		recordCandidate(athlete, field, candidate);
		return true;
	}

	private void recordCandidate(User athlete, ThresholdField field, Candidate candidate) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setSourceActivity(activityRepository.getReferenceById(candidate.activityId()));
		entry.setEffectiveFrom(candidate.date());
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
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		if (latest == null) {
			return true;
		}
		return ChronoUnit.DAYS.between(latest.getEffectiveFrom(), effectiveAsOf) > athlete.getThresholdWindowDays();
	}

	/** The bulk "recompute history from oldest" tool - replaces the entire ledger for one field
	 * with a fresh replay, then syncs the athlete's cached profile value to the result (or clears
	 * it if the athlete has no qualifying activities for this field at all). Calls
	 * onProgress(current, total) after each activity considered, for the SSE bulk-rebuild
	 * endpoint. Returns the final entry count. */
	@Transactional
	public int rebuildHistory(User athlete, ThresholdField field, BiConsumer<Integer, Integer> onProgress) {
		thresholdHistoryRepository.deleteByAthleteIdAndField(athlete.getId(), field);
		List<ThresholdHistoryEntry> entries = calculator.replayFullHistory(athlete, field, onProgress);
		for (ThresholdHistoryEntry entry : entries) {
			ThresholdHistory row = new ThresholdHistory();
			row.setAthlete(athlete);
			row.setField(field);
			row.setSourceActivity(activityRepository.getReferenceById(entry.activityId()));
			row.setEffectiveFrom(entry.effectiveFrom());
			if (field == ThresholdField.THRESHOLD_PACE) {
				row.setValuePace(secondsToMmss(entry.value()));
			}
			else {
				row.setValueNumeric((int) Math.round(entry.value()));
			}
			thresholdHistoryRepository.save(row);
		}

		ThresholdHistoryEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
		switch (field) {
			case FTP -> athlete.setFtp(last != null ? (int) Math.round(last.value()) : null);
			case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower(last != null ? (int) Math.round(last.value()) : null);
			case THRESHOLD_PACE -> athlete.setThresholdPace(last != null ? secondsToMmss(last.value()) : "");
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

	/** GET /v1/athletes/{id}/thresholds' per-field entry: current + previous value, and whether
	 * the current one has aged out of the window. */
	public ThresholdSummaryEntry summaryFor(User athlete, ThresholdField field) {
		List<ThresholdHistory> entries =
				thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field);
		if (entries.isEmpty()) {
			return new ThresholdSummaryEntry(null, null, null, null, true);
		}
		ThresholdHistory current = entries.get(0);
		ThresholdHistory previous = entries.size() > 1 ? entries.get(1) : null;
		boolean stale = ChronoUnit.DAYS.between(current.getEffectiveFrom(), LocalDate.now()) > athlete.getThresholdWindowDays();
		return new ThresholdSummaryEntry(valueOf(field, current), previous != null ? valueOf(field, previous) : null,
				sourceActivityIdOf(current), current.getEffectiveFrom(), stale);
	}

	/** GET /v1/athletes/{id}/threshold-history?field=... - the full ledger, most recent first. */
	public List<ThresholdHistoryEntryResponse> ledgerFor(User athlete, ThresholdField field) {
		return thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).stream()
				.map(entry -> new ThresholdHistoryEntryResponse(
						valueOf(field, entry), sourceActivityIdOf(entry), entry.getEffectiveFrom()))
				.toList();
	}
}
