package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #616: excluding an activity from threshold detection for one field - the service behind
 * POST/GET/DELETE /v1/athletes/{id}/threshold-exclusions. Excluding (or removing an exclusion)
 * rebuilds that field's ledger from the activity's date and re-rates TSS/intensity from there,
 * through the same {@link ActivityService#recomputeAfterDuplicateChange} cascade a
 * duplicate-status change uses, since both take one activity out of (or put it back into)
 * threshold detection. Mirrors the Python backend's athletes/threshold_exclusions.py.
 */
@Service
public class ThresholdExclusionService {

	/** A ledger row's identity for the before/after diff - everything but its id, which a rebuild
	 * changes even when the row itself comes back identical. */
	public record LedgerRow(ThresholdHistoryKind kind, String sourceActivityId, LocalDate effectiveFrom,
			LocalDate currentFrom, Object value) {
	}

	public record Effect(ThresholdField field, String activityId, boolean excluded, boolean preview, Object valueBefore,
			Object valueAfter, List<LedgerRow> rowsRemoved, List<LedgerRow> rowsAdded) {
	}

	public record Exclusion(ThresholdField field, String activityId, String activityName, LocalDate activityDate,
			String setByUserId, Instant createdAt) {
	}

	private final ExcludedThresholdCandidateRepository excludedRepository;
	private final AcceptedThresholdCandidateRepository acceptedRepository;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ActivityRepository activityRepository;
	private final ThresholdHistoryService thresholdHistoryService;
	private final TssRecomputeService tssRecomputeService;
	private final UserRepository userRepository;
	private final ThresholdSuggestionService thresholdSuggestionService;
	private final TransactionTemplate transactionTemplate;

	public ThresholdExclusionService(ExcludedThresholdCandidateRepository excludedRepository,
			AcceptedThresholdCandidateRepository acceptedRepository, ThresholdHistoryRepository thresholdHistoryRepository,
			ActivityRepository activityRepository, ThresholdHistoryService thresholdHistoryService,
			TssRecomputeService tssRecomputeService, UserRepository userRepository,
			ThresholdSuggestionService thresholdSuggestionService, PlatformTransactionManager transactionManager) {
		this.excludedRepository = excludedRepository;
		this.acceptedRepository = acceptedRepository;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.activityRepository = activityRepository;
		this.thresholdHistoryService = thresholdHistoryService;
		this.tssRecomputeService = tssRecomputeService;
		this.userRepository = userRepository;
		this.thresholdSuggestionService = thresholdSuggestionService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public static ThresholdField parseField(String field) {
		if (field == null) {
			throw new ValidationException("Must be one of critical_run_power, ftp, threshold_pace.", "field");
		}
		try {
			return ThresholdField.fromWireValue(field);
		}
		catch (IllegalArgumentException e) {
			throw new ValidationException("Must be one of critical_run_power, ftp, threshold_pace.", "field");
		}
	}

	private static Sport sportFor(ThresholdField field) {
		return field == ThresholdField.FTP ? Sport.BIKE : Sport.RUN;
	}

	private Activity activityFor(User athlete, ThresholdField field, String activityId) {
		Activity activity = activityRepository.findByIdAndAthleteId(activityId, athlete.getId())
				.orElseThrow(() -> new NotFoundException("No such activity."));
		if (activity.getSport() != sportFor(field)) {
			throw new ValidationException("A " + activity.getSport().wireValue() + " activity can't define "
					+ field.wireValue() + ".", "activity_id");
		}
		return activity;
	}

	private List<LedgerRow> ledgerFrom(User athlete, ThresholdField field, LocalDate fromDate) {
		return thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), field)
				.stream()
				.filter(row -> !row.getCurrentFrom().isBefore(fromDate))
				.map(row -> new LedgerRow(row.getKind(),
						row.getSourceActivity() != null ? row.getSourceActivity().getId() : null, row.getEffectiveFrom(),
						row.getCurrentFrom(), field == ThresholdField.THRESHOLD_PACE ? row.getValuePace() : row.getValueNumeric()))
				.sorted(Comparator.comparing(LedgerRow::currentFrom).thenComparing(LedgerRow::effectiveFrom)
						.thenComparing(row -> row.kind().name())
						.thenComparing(row -> row.sourceActivityId() != null ? row.sourceActivityId() : ""))
				.toList();
	}

	private Object liveValue(String athleteId, ThresholdField field) {
		User fresh = userRepository.findById(athleteId).orElseThrow();
		return switch (field) {
			case FTP -> fresh.getFtp();
			case CRITICAL_RUN_POWER -> fresh.getCriticalRunPower();
			// Unset is "" on the entity - null on the wire, as Django reports it.
			case THRESHOLD_PACE -> fresh.getThresholdPace() == null || fresh.getThresholdPace().isBlank()
					? null : fresh.getThresholdPace();
		};
	}

	/** Runs {@code change} (the row write) plus the ledger rebuild it triggers, diffing the ledger
	 * from the activity's date before and after. {@code preview} rolls all of it back, so the diff
	 * is exactly what a real run would do, with nothing persisted. */
	private Effect apply(User athlete, ThresholdField field, Activity activity, BooleanSupplier change, boolean preview,
			boolean excluded) {
		LocalDate fromDate = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
		boolean[] changed = { false };
		Effect effect = transactionTemplate.execute(status -> {
			Object valueBefore = liveValue(athlete.getId(), field);
			List<LedgerRow> before = ledgerFrom(athlete, field, fromDate);
			changed[0] = change.getAsBoolean();
			if (changed[0]) {
				// The same ledger rebuild a duplicate-status change runs (recomputeAfterDuplicateChange),
				// but the TSS re-rate after it is narrower: only this field's sport is rated against
				// its ledger, and a preview skips it entirely - the effect reports only the ledger
				// and the live value, which the re-rate doesn't touch, and re-rating every later
				// activity from its per-second records is by far the slowest step (an exclusion
				// months back could otherwise outlast the MCP proxy's timeout, twice per change).
				thresholdHistoryService.rebuildHistoryFrom(athlete, field, fromDate, (current, total) -> { });
				if (!preview) {
					tssRecomputeService.recomputeForAthleteFrom(athlete, fromDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
							sportFor(field));
				}
			}
			List<LedgerRow> after = ledgerFrom(athlete, field, fromDate);
			Object valueAfter = liveValue(athlete.getId(), field);
			if (preview) {
				status.setRollbackOnly();
			}
			Set<LedgerRow> beforeSet = new HashSet<>(before);
			Set<LedgerRow> afterSet = new HashSet<>(after);
			return new Effect(field, activity.getId(), excluded, preview, valueBefore, valueAfter,
					before.stream().filter(row -> !afterSet.contains(row)).toList(),
					after.stream().filter(row -> !beforeSet.contains(row)).toList());
		});
		if (!preview && changed[0]) {
			// The ledger was rebuilt, and the activity left (or rejoined) every scan - so the cached
			// suggestion list may now be wrong, e.g. still offering the excluded ride as a
			// "rejected" candidate. Same rule as every other ThresholdHistory write.
			thresholdSuggestionService.invalidate(athlete.getId());
		}
		return effect;
	}

	/** Excludes {@code activityId} from {@code field}'s threshold detection, removing any
	 * acceptance of the same (field, activity) - exclusion wins - and rebuilds the ledger from the
	 * activity's date. Idempotent: excluding an already-excluded activity changes nothing. */
	public Effect exclude(User athlete, ThresholdField field, String activityId, User setBy, boolean preview) {
		Activity activity = activityFor(athlete, field, activityId);
		return apply(athlete, field, activity, () -> {
			acceptedRepository.deleteByAthleteIdAndFieldAndActivityId(athlete.getId(), field, activity.getId());
			return excludedRepository.insertIfAbsent(athlete.getId(), field.wireValue(), activity.getId(),
					setBy != null ? setBy.getId() : null) > 0;
		}, preview, true);
	}

	/** Removes the exclusion, so the activity counts again, and rebuilds the ledger from its date.
	 * 404 if the activity isn't excluded for {@code field}. */
	public Effect include(User athlete, ThresholdField field, String activityId, boolean preview) {
		Activity activity = activityFor(athlete, field, activityId);
		if (excludedRepository.findByAthleteIdAndFieldAndActivityId(athlete.getId(), field, activity.getId()).isEmpty()) {
			throw new NotFoundException("That activity isn't excluded for this field.");
		}
		return apply(athlete, field, activity, () -> {
			excludedRepository.deleteByAthleteIdAndFieldAndActivityId(athlete.getId(), field, activity.getId());
			return true;
		}, preview, false);
	}

	@Transactional(readOnly = true)
	public List<Exclusion> list(User athlete, ThresholdField field, String activityId) {
		List<Exclusion> result = new ArrayList<>();
		for (ThresholdField f : field != null ? EnumSet.of(field) : EnumSet.allOf(ThresholdField.class)) {
			for (ExcludedThresholdCandidate row : excludedRepository.findByAthleteIdAndField(athlete.getId(), f)) {
				Activity activity = row.getActivity();
				if (activityId != null && !activityId.equals(activity.getId())) {
					continue;
				}
				result.add(new Exclusion(f, activity.getId(), activity.getName(),
						activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(),
						row.getSetBy() != null ? row.getSetBy().getId() : null, row.getCreatedAt()));
			}
		}
		result.sort(Comparator.comparing(Exclusion::activityDate).thenComparing(e -> e.field().wireValue()));
		return result;
	}
}
