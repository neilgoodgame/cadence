package com.cadence.api.athletes;

import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ThresholdHistoryCalculator.Candidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.RejectedCandidate;
import com.cadence.api.athletes.ThresholdHistoryCalculator.UpcomingDropResult;
import com.cadence.api.athletes.dto.ThresholdSuggestionResponse;
import com.cadence.api.athletes.dto.ThresholdSuggestionResponse.ImpliedFrom;
import com.cadence.api.athletes.dto.ThresholdSuggestionResponse.RaceInfo;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Threshold suggestions feature's API-facing suggestion list - formatting the raw
 * detection methods on {@link ThresholdHistoryCalculator} (rejectedCandidates/upcomingDrop) into
 * the API's JSON shape, filtering out dismissed occurrences, and caching the result per athlete
 * for 24 hours (the detection scan reads every record of every activity in the trailing window -
 * too slow to re-run on every Dashboard/Thresholds-screen load). Also owns accept/dismiss/undo,
 * since those need the AcceptedThresholdCandidate/ThresholdSuggestionDismissal repositories
 * ThresholdHistoryCalculator deliberately stays unaware of (it's pure/read-only; these are
 * writes).
 *
 * <p>Caching: a plain in-process {@link ConcurrentHashMap}, not Spring's {@code @Cacheable} (no
 * CacheManager is configured anywhere in this codebase, and the custom per-athlete invalidation
 * wiring at many call sites - ingest, import, threshold writes, settings changes, race changes,
 * accept/dismiss - doesn't fit an annotation-driven cache cleanly). Deliberately fine for this
 * app: it runs as a single backend process per environment (see AWS_MIGRATION_PLAN.md's actual
 * deployed architecture - one EC2 instance, no horizontal scaling), so a per-process cache
 * behaves exactly like a shared one would.
 */
@Service
public class ThresholdSuggestionService {

	private static final long CACHE_TTL_SECONDS = 24 * 60 * 60;

	private record CacheEntry(List<ThresholdSuggestionResponse> data, Instant cachedAt) {
	}

	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	private final ThresholdHistoryCalculator calculator;
	private final ThresholdHistoryService thresholdHistoryService;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ThresholdSuggestionDismissalRepository dismissalRepository;
	private final AcceptedThresholdCandidateRepository acceptedRepository;
	private final ActivityRepository activityRepository;
	private final UserRepository userRepository;

	public ThresholdSuggestionService(ThresholdHistoryCalculator calculator, ThresholdHistoryService thresholdHistoryService,
			ThresholdHistoryRepository thresholdHistoryRepository, ThresholdSuggestionDismissalRepository dismissalRepository,
			AcceptedThresholdCandidateRepository acceptedRepository, ActivityRepository activityRepository,
			UserRepository userRepository) {
		this.calculator = calculator;
		this.thresholdHistoryService = thresholdHistoryService;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.dismissalRepository = dismissalRepository;
		this.acceptedRepository = acceptedRepository;
		this.activityRepository = activityRepository;
		this.userRepository = userRepository;
	}

	/** Called wherever something could change the suggestion list: activity ingest/import, any
	 * ThresholdHistory write (manual edit, refresh, rebuild, accept), a change to
	 * thresholdWindowDays/thresholdSanityPct/thresholdWarningDays/ftpCalculationMethod, a race
	 * create/update/delete, or a dismiss. */
	public void invalidate(String athleteId) {
		cache.remove(athleteId);
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

	private static Object formatValue(ThresholdField field, double raw) {
		return field == ThresholdField.THRESHOLD_PACE ? secondsToMmss(raw) : (int) Math.round(raw);
	}

	private static Object currentRawValue(User athlete, ThresholdField field) {
		return switch (field) {
			case FTP -> athlete.getFtp();
			case CRITICAL_RUN_POWER -> athlete.getCriticalRunPower();
			case THRESHOLD_PACE -> athlete.getThresholdPace();
		};
	}

	private static Double athleteReferenceValue(User athlete, ThresholdField field) {
		return switch (field) {
			case FTP -> athlete.getFtp() == null ? null : athlete.getFtp().doubleValue();
			case CRITICAL_RUN_POWER -> athlete.getCriticalRunPower() == null ? null : athlete.getCriticalRunPower().doubleValue();
			case THRESHOLD_PACE -> mmssToSeconds(athlete.getThresholdPace());
		};
	}

	private static Double deltaPct(Double current, Double proposed) {
		if (current == null || current == 0 || proposed == null) {
			return null;
		}
		return Math.round(Math.abs(proposed - current) / current * 1000) / 10.0;
	}

	private ThresholdSuggestionResponse rejectedSuggestion(User athlete, ThresholdField field) {
		Set<String> dismissedKeys =
				dismissalRepository.findByAthleteIdAndFieldAndKind(athlete.getId(), field, ThresholdSuggestionDismissal.Kind.REJECTED)
						.stream().map(ThresholdSuggestionDismissal::getKey).collect(Collectors.toSet());
		Double reference = athleteReferenceValue(athlete, field);
		for (RejectedCandidate candidate : calculator.rejectedCandidates(athlete, field, null)) {
			if (dismissedKeys.contains(candidate.activityId())) {
				continue;
			}
			return new ThresholdSuggestionResponse(
					field.wireValue() + ":rejected:" + candidate.activityId(),
					field.wireValue(), "rejected",
					currentRawValue(athlete, field),
					formatValue(field, candidate.impliedValue()),
					deltaPct(reference, candidate.impliedValue()),
					candidate.activityId(), candidate.date(),
					new ImpliedFrom(candidate.window(), Math.round(candidate.rawValue())),
					null, null, null);
		}
		return null;
	}

	private ThresholdSuggestionResponse upcomingSuggestion(User athlete, ThresholdField field) {
		UpcomingDropResult result = calculator.upcomingDrop(athlete, field, null);
		if (result == null) {
			return null;
		}

		if (result instanceof UpcomingDropResult.RaceWillRefresh raceWillRefresh) {
			var race = raceWillRefresh.race();
			return new ThresholdSuggestionResponse(
					field.wireValue() + ":race_will_refresh:" + race.getId(),
					field.wireValue(), "race_will_refresh",
					null, null, null, null, null, null,
					raceWillRefresh.expiry(), raceWillRefresh.daysLeft(),
					new RaceInfo(race.getId(), race.getName(), race.getDate()));
		}

		UpcomingDropResult.UpcomingDrop drop = (UpcomingDropResult.UpcomingDrop) result;
		ThresholdHistory currentEntry = drop.currentEntry();
		String sourceActivityId = currentEntry.getSourceActivity() != null ? currentEntry.getSourceActivity().getId() : null;
		String key = (sourceActivityId != null ? sourceActivityId : "manual") + ":" + drop.expiry();
		if (dismissalRepository.existsByAthleteIdAndFieldAndKindAndKey(
				athlete.getId(), field, ThresholdSuggestionDismissal.Kind.UPCOMING_DROP, key)) {
			return null;
		}
		Double reference = athleteReferenceValue(athlete, field);
		Candidate successor = drop.successor();
		return new ThresholdSuggestionResponse(
				field.wireValue() + ":upcoming_drop:" + key,
				field.wireValue(), "upcoming_drop",
				currentRawValue(athlete, field),
				successor != null ? formatValue(field, successor.impliedValue()) : null,
				successor != null ? deltaPct(reference, successor.impliedValue()) : null,
				sourceActivityId, currentEntry.getEffectiveFrom(),
				null, drop.expiry(), drop.daysLeft(), null);
	}

	private List<ThresholdSuggestionResponse> build(User athlete) {
		List<ThresholdSuggestionResponse> suggestions = new ArrayList<>();
		for (ThresholdField field : ThresholdField.values()) {
			ThresholdSuggestionResponse rejected = rejectedSuggestion(athlete, field);
			if (rejected != null) {
				suggestions.add(rejected);
			}
			ThresholdSuggestionResponse upcoming = upcomingSuggestion(athlete, field);
			if (upcoming != null) {
				suggestions.add(upcoming);
			}
		}
		// Rejected first, then upcoming_drop/race_will_refresh by daysLeft ascending - matches
		// the Dashboard banner's own eligibility order, and is a sensible default for the
		// Thresholds & zones screen's full list too.
		suggestions.sort(Comparator
				.comparingInt((ThresholdSuggestionResponse s) -> "rejected".equals(s.kind()) ? 0 : 1)
				.thenComparingInt(s -> s.daysLeft() != null ? s.daysLeft() : 0));
		return suggestions;
	}

	public List<ThresholdSuggestionResponse> listSuggestions(User athlete) {
		CacheEntry entry = cache.get(athlete.getId());
		List<ThresholdSuggestionResponse> data;
		if (entry == null || Instant.now().isAfter(entry.cachedAt().plusSeconds(CACHE_TTL_SECONDS))) {
			data = build(athlete);
			cache.put(athlete.getId(), new CacheEntry(data, Instant.now()));
		}
		else {
			data = entry.data();
		}
		LocalDate today = LocalDate.now();
		return data.stream()
				.map(s -> s.expiryDate() != null ? s.withDaysLeft((int) ChronoUnit.DAYS.between(today, s.expiryDate())) : s)
				.toList();
	}

	private Optional<ThresholdSuggestionResponse> findSuggestion(User athlete, String suggestionId) {
		return listSuggestions(athlete).stream().filter(s -> s.id().equals(suggestionId)).findFirst();
	}

	/** Accepts a "rejected" suggestion: records it as a real ledger entry (bypassing the sanity
	 * band this once and forever after, via AcceptedThresholdCandidate - see
	 * ThresholdHistoryCalculator's acceptedActivityIds), and updates the athlete's cached profile
	 * value. Returns the new current ThresholdHistory entry, or null if the suggestion no longer
	 * exists (already handled, or the data moved on since it was last listed). */
	@Transactional
	public ThresholdHistory accept(User athlete, String suggestionId) {
		ThresholdSuggestionResponse suggestion = findSuggestion(athlete, suggestionId).orElse(null);
		if (suggestion == null || !"rejected".equals(suggestion.kind())) {
			return null;
		}
		ThresholdField field = ThresholdField.fromWireValue(suggestion.field());
		String activityId = suggestion.activityId();

		// Re-derive the candidate fresh rather than trusting the cached suggestion's own implied
		// value - the accept has to be correct even if the cache is momentarily stale.
		Candidate candidate = calculator.rejectedCandidates(athlete, field, null).stream()
				.filter(c -> c.activityId().equals(activityId))
				.map(c -> new Candidate(c.activityId(), c.date(), c.impliedValue()))
				.findFirst().orElse(null);
		if (candidate == null) {
			return null;
		}

		if (acceptedRepository.findByAthleteIdAndFieldAndActivityId(athlete.getId(), field, activityId).isEmpty()) {
			AcceptedThresholdCandidate accepted = new AcceptedThresholdCandidate();
			accepted.setAthlete(athlete);
			accepted.setField(field);
			accepted.setActivity(activityRepository.getReferenceById(activityId));
			acceptedRepository.save(accepted);
		}
		thresholdHistoryService.recordCandidate(athlete, field, candidate, LocalDate.now());
		invalidate(athlete.getId());
		return thresholdHistoryRepository.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
				.orElse(null);
	}

	/** Removes the AcceptedThresholdCandidate override and re-runs the normal windowed recompute
	 * - the candidate goes back to being sanity-filtered like any other activity, which may or
	 * may not still pick it (or something else) as current.
	 *
	 * <p>Deletes the ThresholdHistory row accept() created and restores the athlete's live field
	 * to whatever the now-latest remaining entry says *before* recomputing - not an optional
	 * tidy-up. The sanity band checks the candidate against the athlete's *current live profile
	 * value* (athleteReferenceValue), and accept() leaves that live value set to the very
	 * candidate being undone; recomputing without restoring it first would compare the rejected
	 * candidate against itself (0% deviation, trivially "within band"), silently re-accepting
	 * it. */
	@Transactional
	public void undoAccept(User athlete, ThresholdField field, String activityId) {
		acceptedRepository.deleteByAthleteIdAndFieldAndActivityId(athlete.getId(), field, activityId);
		thresholdHistoryRepository.deleteByAthleteIdAndFieldAndSourceActivityId(athlete.getId(), field, activityId);
		ThresholdHistory latest = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field).orElse(null);
		switch (field) {
			case FTP -> athlete.setFtp(latest != null ? latest.getValueNumeric() : null);
			case CRITICAL_RUN_POWER -> athlete.setCriticalRunPower(latest != null ? latest.getValueNumeric() : null);
			case THRESHOLD_PACE -> athlete.setThresholdPace(latest != null ? latest.getValuePace() : "");
		}
		userRepository.save(athlete);
		thresholdHistoryService.refreshField(athlete, field);
		invalidate(athlete.getId());
	}

	@Transactional
	public boolean dismiss(User athlete, String suggestionId) {
		ThresholdSuggestionResponse suggestion = findSuggestion(athlete, suggestionId).orElse(null);
		if (suggestion == null || "race_will_refresh".equals(suggestion.kind())) {
			return false; // race_will_refresh is a quiet note, not a dismissable card
		}
		ThresholdField field = ThresholdField.fromWireValue(suggestion.field());
		ThresholdSuggestionDismissal.Kind kind =
				"rejected".equals(suggestion.kind()) ? ThresholdSuggestionDismissal.Kind.REJECTED : ThresholdSuggestionDismissal.Kind.UPCOMING_DROP;
		String prefix = suggestion.field() + ":" + suggestion.kind() + ":";
		String key = suggestionId.substring(prefix.length());
		if (!dismissalRepository.existsByAthleteIdAndFieldAndKindAndKey(athlete.getId(), field, kind, key)) {
			ThresholdSuggestionDismissal dismissal = new ThresholdSuggestionDismissal();
			dismissal.setAthlete(athlete);
			dismissal.setField(field);
			dismissal.setKind(kind);
			dismissal.setKey(key);
			dismissalRepository.save(dismissal);
		}
		invalidate(athlete.getId());
		return true;
	}

	@Transactional
	public void undoDismiss(User athlete, ThresholdField field, ThresholdSuggestionDismissal.Kind kind, String key) {
		dismissalRepository.deleteByAthleteIdAndFieldAndKindAndKey(athlete.getId(), field, kind, key);
		invalidate(athlete.getId());
	}
}
