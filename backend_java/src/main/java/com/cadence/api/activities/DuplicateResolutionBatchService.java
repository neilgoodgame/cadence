package com.cadence.api.activities;

import com.cadence.api.activities.ActivityOverlapDetectionService.OverlapResolution;
import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.gear.GearMileageService;
import com.cadence.api.users.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #498's resolveDuplicatesBatch/undoBatch MCP tools: applies mark/merge to many overlap
 * pairs at once - either an explicit list, or everything the overlap query finds - with a
 * dry-run preview (the default) and, once actually applied, a persisted
 * {@link DuplicateResolutionBatch} so the whole thing can be undone later. Mirrors the Python
 * backend's activities/duplicate_batches.py - keep the two in sync when changing either.
 */
@Service
public class DuplicateResolutionBatchService {

	/** A deliberately bounded, one-shot action - unlike the backfill command (meant to walk an
	 * athlete's entire history unattended), this keeps a single MCP call's cost, and the undo
	 * surface it creates, small and reviewable. */
	public static final int MAX_BATCH_PAIRS = 100;

	private final ActivityOverlapService overlapService;
	private final ActivityOverlapDetectionService detectionService;
	private final ActivityRepository activityRepository;
	private final ActivityService activityService;
	private final GearMileageService gearMileageService;
	private final DuplicateResolutionBatchRepository batchRepository;
	private final DuplicateResolutionEntryRepository entryRepository;

	public DuplicateResolutionBatchService(ActivityOverlapService overlapService,
			ActivityOverlapDetectionService detectionService, ActivityRepository activityRepository,
			ActivityService activityService, GearMileageService gearMileageService,
			DuplicateResolutionBatchRepository batchRepository, DuplicateResolutionEntryRepository entryRepository) {
		this.overlapService = overlapService;
		this.detectionService = detectionService;
		this.activityRepository = activityRepository;
		this.activityService = activityService;
		this.gearMileageService = gearMileageService;
		this.batchRepository = batchRepository;
		this.entryRepository = entryRepository;
	}

	public record ResolveBatchResult(boolean dryRun, String batchId, List<PairResult> results) {
	}

	public record PairResult(String activityId, String otherActivityId, OverlapAction action, String primaryActivityId) {
	}

	public record UndoBatchResult(String batchId, int revertedCount, int skippedCount) {
	}

	/** Explicit-pairs mode: at most {@value #MAX_BATCH_PAIRS} [activityId, otherActivityId]
	 * pairs, each pair's own two ids both belonging to {@code athlete} (checked here, same
	 * ownership guarantee the query mode gets for free from the overlap query's own athlete
	 * scoping). */
	public record ExplicitPair(String activityId, String otherActivityId) {
	}

	/** Query mode's own filters - the exact same parameters {@code list_overlapping_activities}/
	 * {@code GET /v1/activities/overlaps} takes. */
	public record OverlapQueryFilters(String activityId, Sport sport, boolean crossSport, String device,
			String otherDevice, Environment environment, LocalDate from, LocalDate to, Integer minDurationSeconds,
			List<String> otherHas, Double minOverlapPct, Long maxStartDeltaSeconds, Integer limit) {

		public boolean isEmpty() {
			return activityId == null && sport == null && !crossSport && device == null && otherDevice == null
					&& environment == null && from == null && to == null && minDurationSeconds == null
					&& (otherHas == null || otherHas.isEmpty()) && minOverlapPct == null && maxStartDeltaSeconds == null;
		}

		public static OverlapQueryFilters empty() {
			return new OverlapQueryFilters(null, null, false, null, null, null, null, null, null, null, null, null, null);
		}
	}

	@Transactional
	public ResolveBatchResult resolveDuplicatesBatch(User athlete, OverlapHandling action,
			List<ExplicitPair> pairs, OverlapQueryFilters queryFilters, boolean dryRun, User setBy) {
		if (action != OverlapHandling.MARK && action != OverlapHandling.MERGE) {
			throw new ValidationException("action must be 'mark' or 'merge'.", "action");
		}
		boolean queryFiltersGiven = queryFilters != null && !queryFilters.isEmpty();
		if (pairs != null && !pairs.isEmpty() && queryFiltersGiven) {
			throw new ValidationException("Pass either pairs or the overlap query's filters, not both.", "pairs");
		}

		List<String[]> candidatePairs = (pairs != null && !pairs.isEmpty())
				? validatedExplicitPairs(athlete, pairs)
				: queriedPairs(athlete, queryFilters != null ? queryFilters : OverlapQueryFilters.empty());

		if (dryRun) {
			List<PairResult> results = new ArrayList<>();
			for (String[] pair : candidatePairs) {
				OverlapResolution resolution = detectionService.previewResolution(pair[0], pair[1], action);
				results.add(new PairResult(pair[0], resolution.otherActivityId(), resolution.action(),
						resolution.primaryActivityId()));
			}
			return new ResolveBatchResult(true, null, results);
		}

		DuplicateResolutionBatch batch = new DuplicateResolutionBatch();
		batch.setAthlete(athlete);
		batch.setAction(action);
		batch.setCreatedBy(setBy);
		batch = batchRepository.save(batch);

		List<PairResult> results = new ArrayList<>();
		boolean affected = false;
		for (String[] pair : candidatePairs) {
			OverlapResolution resolution = detectionService.resolvePair(pair[0], pair[1], action);

			DuplicateResolutionEntry entry = new DuplicateResolutionEntry();
			entry.setBatch(batch);
			entry.setActivity(activityRepository.findById(pair[0]).orElseThrow());
			entry.setOtherActivity(activityRepository.findById(pair[1]).orElse(null));
			entry.setOutcome(resolution.action());
			if (resolution.action() == OverlapAction.MARKED) {
				entry.setPrimaryActivity(activityRepository.findById(resolution.primaryActivityId()).orElseThrow());
				affected = true;
			}
			entryRepository.save(entry);

			results.add(new PairResult(pair[0], resolution.otherActivityId(), resolution.action(),
					resolution.primaryActivityId()));
		}

		if (affected) {
			activityService.recomputeAfterDuplicateChange(athlete);
		}

		return new ResolveBatchResult(false, batch.getId(), results);
	}

	/** Reverses a {@link DuplicateResolutionBatch}'s {@code MARKED} entries (a {@code FLAGGED}
	 * entry never changed anything, so there's nothing to reverse for it) - unmarking each
	 * duplicate exactly like {@code ActivityService#unmarkDuplicate} does, including restoring
	 * its gear mileage. An entry is skipped, not forced, if its activity's primary no longer
	 * matches what this batch set it to - it was re-resolved or hand-edited since, and blindly
	 * reverting it could clobber that. */
	@Transactional
	public UndoBatchResult undoBatch(User athlete, String batchId) {
		DuplicateResolutionBatch batch = batchRepository.findByIdAndAthleteId(batchId, athlete.getId())
				.orElseThrow(() -> new NotFoundException("No such batch: " + batchId));
		if (batch.getUndoneAt() != null) {
			throw new ValidationException("This batch has already been undone.", "batchId");
		}

		int reverted = 0;
		int skipped = 0;
		boolean affected = false;
		for (DuplicateResolutionEntry entry : entryRepository.findByBatchAndOutcome(batch, OverlapAction.MARKED)) {
			Activity activity = entry.getActivity();
			String currentPrimaryId = activity.getPrimaryActivity() == null ? null : activity.getPrimaryActivity().getId();
			String entryPrimaryId = entry.getPrimaryActivity() == null ? null : entry.getPrimaryActivity().getId();
			if (!Objects.equals(currentPrimaryId, entryPrimaryId)) {
				skipped++;
				continue;
			}
			activity.setPrimaryActivity(null);
			Activity saved = activityRepository.save(activity);
			String bikeId = saved.getBike() == null ? null : saved.getBike().getId();
			String shoeId = saved.getShoe() == null ? null : saved.getShoe().getId();
			gearMileageService.applyDuplicateChange(bikeId, shoeId, saved.getDistanceKm(), true, false);
			reverted++;
			affected = true;
		}

		batch.setUndoneAt(Instant.now());
		batchRepository.save(batch);

		if (affected) {
			activityService.recomputeAfterDuplicateChange(athlete);
		}

		return new UndoBatchResult(batch.getId(), reverted, skipped);
	}

	private List<String[]> validatedExplicitPairs(User athlete, List<ExplicitPair> pairs) {
		if (pairs.size() > MAX_BATCH_PAIRS) {
			throw new ValidationException("At most " + MAX_BATCH_PAIRS + " pairs per batch.", "pairs");
		}
		List<String[]> validated = new ArrayList<>();
		for (ExplicitPair pair : pairs) {
			activityRepository.findByIdAndAthleteId(pair.activityId(), athlete.getId())
					.orElseThrow(() -> new NotFoundException("No such activity: " + pair.activityId()));
			activityRepository.findByIdAndAthleteId(pair.otherActivityId(), athlete.getId())
					.orElseThrow(() -> new NotFoundException("No such activity: " + pair.otherActivityId()));
			validated.add(new String[] { pair.activityId(), pair.otherActivityId() });
		}
		return validated;
	}

	private List<String[]> queriedPairs(User athlete, OverlapQueryFilters filters) {
		int limit = Math.min(filters.limit() != null ? filters.limit() : MAX_BATCH_PAIRS, MAX_BATCH_PAIRS);
		List<String> otherHas = filters.otherHas() != null ? filters.otherHas() : List.of();
		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				athlete.getId(), filters.activityId(), filters.sport(), filters.crossSport(), filters.device(),
				filters.otherDevice(), filters.environment(), filters.from(), filters.to(),
				filters.minDurationSeconds(), otherHas, filters.minOverlapPct(), filters.maxStartDeltaSeconds(),
				// Resolving an already-resolved pair makes no sense - forced regardless of input.
				false, null, limit);
		List<String[]> pairs = new ArrayList<>();
		for (OverlapPairResponse pair : page.data()) {
			pairs.add(new String[] { pair.anchor().id(), pair.other().id() });
		}
		return pairs;
	}
}
