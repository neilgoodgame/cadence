package com.cadence.api.activities;

import com.cadence.api.activities.ActivityMergeApplyService.FieldRewriteCounts;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.AlignmentResult;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.Sample;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #501 (phase 1, part 4): the merge orchestrator - resolves (or accepts an override of)
 * the time offset, rewrites the target's fields from the source, runs the shared
 * post-processing chain, optionally hides the source as a #498 duplicate, and records a
 * {@link Merge} audit row.
 *
 * <p>{@code preview=true} runs the exact same code inside this method's own transaction, then
 * throws {@link MergePreviewRollbackSignal} to force a rollback instead of returning normally -
 * see that class's Javadoc, and athletes' {@code PreviewRollbackSignal} it mirrors, for why a
 * nested rollback-only flag doesn't work here. So preview and a real merge can never disagree
 * about outcome, and preview has zero side effects even though it isn't a separate,
 * hand-simulated computation. Mirrors backend's activities/merge_service.py - keep the two in
 * sync when changing either.
 */
@Service
public class ActivityMergeService {

	public record ActivitySummary(Integer avgPower, Integer normPower, Double intensity, int tss) {
	}

	public record MergeResult(String mergeId, int offsetSeconds, Double alignmentScore,
			Map<String, FieldRewriteCounts> fieldCounts, ActivitySummary before, ActivitySummary after,
			boolean sourceHidden) {
	}

	/** Issue #501 (phase 3): a target's currently active merge, for {@code get_merge}. {@code sourceId}/
	 * {@code sourceName} are null if the source activity has since been deleted outright (the {@link
	 * Merge#getSourceActivity} association is SET_NULL, not CASCADE - see that field's own Javadoc). */
	public record MergeRecord(String mergeId, String sourceId, String sourceName, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds, Double alignmentScore,
			Map<String, FieldRewriteCounts> fieldCounts, boolean sourceHidden, Instant createdAt) {
	}

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final MergeRepository mergeRepository;
	private final ActivityService activityService;
	private final ActivityMergeApplyService mergeApplyService;
	private final ActivityMergePostProcessingService postProcessingService;

	public ActivityMergeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			MergeRepository mergeRepository, ActivityService activityService,
			ActivityMergeApplyService mergeApplyService, ActivityMergePostProcessingService postProcessingService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.mergeRepository = mergeRepository;
		this.activityService = activityService;
		this.mergeApplyService = mergeApplyService;
		this.postProcessingService = postProcessingService;
	}

	private boolean isMultisportLinked(Activity activity) {
		return activity.getSport() == Sport.MULTISPORT || activity.getSport() == Sport.TRANSITION
				|| activity.getParentActivity() != null;
	}

	private void validate(Activity target, Activity source, List<String> fields, boolean hideSource) {
		if (target.getId().equals(source.getId())) {
			throw new ValidationException("An activity cannot be merged with itself.", "source_id");
		}
		if (!target.getAthlete().getId().equals(source.getAthlete().getId())) {
			throw new ValidationException("Both activities must belong to the same athlete.", "source_id");
		}
		if (fields.isEmpty()) {
			throw new ValidationException("At least one field must be selected.", "fields");
		}
		List<String> unknown = fields.stream().filter(f -> !ActivityMergeApplyService.isKnownField(f)).toList();
		if (!unknown.isEmpty()) {
			throw new ValidationException("Unknown field(s): " + String.join(", ", unknown), "fields");
		}
		if (hideSource) {
			if (source.getPrimaryActivity() != null) {
				throw new ValidationException("This activity is already a duplicate of another activity.", "source_id");
			}
			if (isMultisportLinked(source) || isMultisportLinked(target)) {
				throw new ValidationException(
						"Multisport sessions and their legs cannot be hidden as duplicates.", "source_id");
			}
		}
	}

	private record OffsetResolution(int offsetSeconds, Double score) {
	}

	/** An explicit override skips alignment entirely - its score is reported as null, since
	 * nothing was actually correlated. */
	private OffsetResolution resolveOffset(Activity target, Activity source, Integer offsetSecondsOverride) {
		if (offsetSecondsOverride != null) {
			return new OffsetResolution(offsetSecondsOverride, null);
		}
		int initialOffset = (int) Duration.between(target.getStartDate(), source.getStartDate()).toSeconds();
		List<Sample> targetSamples = toSamples(recordRepository.findByActivityIdOrderByT(target.getId()));
		List<Sample> sourceSamples = toSamples(recordRepository.findByActivityIdOrderByT(source.getId()));
		AlignmentResult result = ActivityMergeAlignmentCalculator.align(targetSamples, sourceSamples, initialOffset);
		return new OffsetResolution(result.offsetSeconds(), result.score());
	}

	private List<Sample> toSamples(List<Record> records) {
		return records.stream().map(r -> new Sample(r.getT(), r.getCadence(), r.getPower())).toList();
	}

	private ActivitySummary summarize(Activity activity) {
		return new ActivitySummary(activity.getAvgPower(), activity.getNormPower(), activity.getIntensity(),
				activity.getTss());
	}

	/** {@code REQUIRES_NEW}, not the default {@code REQUIRED}: issue #501 phase 4's automatic
	 * merge-on-ingest calls this (via {@link ActivityMergeOrchestrator}) from inside {@code
	 * ActivityOverlapDetectionService}'s own already-{@code @Transactional} method, to preview a
	 * candidate merge's alignment score before deciding whether to apply it for real. With plain
	 * {@code REQUIRED}, {@code preview=true}'s rollback-forcing {@link
	 * MergePreviewRollbackSignal} would join (not open) that caller's transaction and mark the
	 * *whole thing* rollback-only - the signal being caught one level up never undoes that, so
	 * the outer transaction would fail to commit even once a later call in the same chain legitimately
	 * applies a real merge. {@code REQUIRES_NEW} gives every call (preview or real) its own
	 * isolated transaction, so a preview's rollback can never affect anything else - this exactly
	 * matches every *existing* caller's behavior too (the REST controller and MCP tool are never
	 * themselves transactional, so {@code REQUIRED} already always opened a fresh transaction for
	 * them in practice). Found by ActivityOverlapDetectionServiceIntegrationTest's merge-mode
	 * tests, not by inspection - a plain unit test calling runMerge directly (no enclosing
	 * transaction) can't reproduce this at all. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public MergeResult runMerge(String targetId, String sourceId, List<String> fields, NullPolicy nullPolicy,
			Integer offsetSecondsOverride, boolean hideSource, boolean preview) {
		Activity target = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		Activity source = activityRepository.findById(sourceId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		validate(target, source, fields, hideSource);

		OffsetResolution offset = resolveOffset(target, source, offsetSecondsOverride);
		ActivitySummary before = summarize(target);
		// Snapshot each requested activity-level field's value before applyMergeFields
		// overwrites it - see Merge.previousActivityValues' own Javadoc for why.
		Map<String, Object> previousActivityValues = new LinkedHashMap<>();
		for (String field : fields) {
			if (ActivityMergeApplyService.isActivityLevelField(field)) {
				previousActivityValues.put(field, ActivityMergeApplyService.getActivityFieldValueForSnapshot(target, field));
			}
		}

		Map<String, FieldRewriteCounts> counts = mergeApplyService.applyMergeFields(targetId, sourceId, fields,
				nullPolicy, offset.offsetSeconds());

		if (hideSource) {
			source.setPrimaryActivity(target);
			activityRepository.save(source);
			activityService.applyDuplicateGearMileageChange(source, false, true);
		}

		// Runs after hiding, not before, so the one post-processing pass (in particular its
		// full-athlete threshold ledger/TSS rebuild) already sees the source excluded as a
		// duplicate when hideSource is set - not a stale view from before the hide took effect.
		postProcessingService.recomputeAfterMerge(targetId);

		Activity reloadedTarget = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		ActivitySummary after = summarize(reloadedTarget);

		if (preview) {
			throw new MergePreviewRollbackSignal(
					new MergeResult(null, offset.offsetSeconds(), offset.score(), counts, before, after, hideSource));
		}

		Merge merge = new Merge();
		merge.setTargetActivity(target);
		merge.setSourceActivity(source);
		merge.setFields(fields);
		merge.setNullPolicy(nullPolicy);
		merge.setOffsetSeconds(offset.offsetSeconds());
		merge.setAlignmentScore(offset.score());
		merge.setFieldCounts(counts);
		merge.setPreviousActivityValues(previousActivityValues);
		merge.setSourceHidden(hideSource);
		merge = mergeRepository.save(merge);

		return new MergeResult(merge.getId(), offset.offsetSeconds(), offset.score(), counts, before, after,
				hideSource);
	}

	/** Issue #501 (phase 3): the target's current active merge, if any - for {@code get_merge}.
	 * Unlike {@link ActivityUnmergeService}'s own undo-time lookup, this never throws on a merge
	 * chain (more than one active merge) - it just reports the most recently applied one, since a
	 * read has no "which one wins" ambiguity to protect against the way an undo does.
	 * {@code @Transactional} keeps the session open for {@code source.getName()} below - see
	 * backend_java/CLAUDE.md's lazy-association gotcha. */
	@Transactional(readOnly = true)
	public MergeRecord getActiveMerge(String targetId) {
		List<Merge> active = mergeRepository.findByTargetActivityId(targetId).stream()
				.filter(m -> m.getUndoneAt() == null)
				.sorted(Comparator.comparing(Merge::getCreatedAt).reversed())
				.toList();
		if (active.isEmpty()) {
			return null;
		}
		Merge merge = active.get(0);
		Activity source = merge.getSourceActivity();
		return new MergeRecord(merge.getId(), source != null ? source.getId() : null,
				source != null ? source.getName() : null, merge.getFields(), merge.getNullPolicy(),
				merge.getOffsetSeconds(), merge.getAlignmentScore(), merge.getFieldCounts(), merge.isSourceHidden(),
				merge.getCreatedAt());
	}
}
