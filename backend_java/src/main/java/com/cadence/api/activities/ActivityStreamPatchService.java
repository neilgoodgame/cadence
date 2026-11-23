package com.cadence.api.activities;

import com.cadence.api.activities.ActivityMergeApplyService.FieldRewriteCounts;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.ActivityMergeApplyService.Segment;
import com.cadence.api.activities.ActivityStreamFlagService.FlagSegment;
import com.cadence.api.activities.ActivityStreamFlagService.Kind;
import com.cadence.api.activities.ActivityStreamFlagService.StreamFlagsResult;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #540 (phase 6): applies/undoes a {@link StreamPatch} - rewriting the activity's primary
 * stream for field, at explicit or live-detected segments, with fromSource's own stored reading
 * there. See {@link ActivityStreamFlagService} for the read-only detection side this reuses for
 * its own {@code segments="flagged"} case; see {@link StreamPatch}'s own Javadoc for why this
 * always targets the primary stream and never a stored {@link ActivitySource}.
 *
 * <p>Reuses {@link ActivityMergeApplyService#computeStreamField}'s segment-restricted rewrite
 * and {@link ActivityMergeService}'s own preview-via-rollback technique (apply for real, then
 * roll back the whole transaction when preview=true) rather than inventing either a second copy
 * routine or a second preview mechanism. Mirrors backend's activities/stream_patch_service.py -
 * keep the two in sync when changing either.
 */
@Service
public class ActivityStreamPatchService {

	// A patch always applies what it's given - there's no "keep original where the source has
	// nothing" choice the way a merge has, since the whole point of calling this is "replace
	// this implausible stretch", not "fill gaps".
	private static final NullPolicy NULL_POLICY = NullPolicy.OVERWRITE;
	// Repair targets a glitch run, not a whole-stream rewrite (that's merge/attach territory) -
	// a bigger request is almost certainly a caller bug. Enforced on segments="flagged" too, not
	// just explicit input - contact_loss's two detectors each already cap an individual run's
	// length, but CadenceLockDetector's merged runs have no length cap of their own, so
	// "flagged" output can't be assumed pre-bounded for every kind. Untested guess, flag in the PR.
	private static final int MAX_SEGMENTS_SECONDS = 3600;

	/** The segments actually applied - always echoed back (not just a count), so a preview and
	 * its later commit can never silently diverge if segments="flagged" and the underlying data
	 * changes between the two. See {@link StreamPatch}'s own "flagged-segment transparency"
	 * note. */
	public record StreamPatchResult(String patchId, String field, String fromSource, String detectedKind,
			List<Segment> segments, FieldRewriteCounts counts) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final ActivityStreamRepository streamRepository;
	private final StreamPatchRepository streamPatchRepository;
	private final ActivityStreamFlagService streamFlagService;
	private final ActivityPostProcessingService postProcessingService;

	public ActivityStreamPatchService(ActivityRepository activityRepository, RecordRepository recordRepository,
			ActivityStreamRepository streamRepository, StreamPatchRepository streamPatchRepository,
			ActivityStreamFlagService streamFlagService, ActivityPostProcessingService postProcessingService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.streamRepository = streamRepository;
		this.streamPatchRepository = streamPatchRepository;
		this.streamFlagService = streamFlagService;
		this.postProcessingService = postProcessingService;
	}

	private static void validateSegments(List<Segment> segments) {
		for (Segment segment : segments) {
			if (segment.endT() < segment.startT()) {
				throw new ValidationException(
						"Segment [" + segment.startT() + ", " + segment.endT() + "] has end before start.", "segments");
			}
		}
		int total = segments.stream().mapToInt(s -> s.endT() - s.startT() + 1).sum();
		if (total > MAX_SEGMENTS_SECONDS) {
			throw new ValidationException("Segments total " + total + "s, more than the " + MAX_SEGMENTS_SECONDS
					+ "s repair cap - that's whole-stream-rewrite territory (merge/attach), not a repair.", "segments");
		}
	}

	private record ResolvedSegments(List<Segment> segments, String detectedKind) {
	}

	/** Parses the wire {@code segments} value - either the literal string {@code "flagged"}
	 * (returns {@code null}, meaning "detect live" - see {@link #resolveSegments}) or a JSON
	 * array of {@code [startT, endT]} pairs (deserialized by Jackson, for an {@code Object}-typed
	 * field, as nested {@code List}s of {@code Integer}). Shared by {@code ActivityController}
	 * and the MCP {@code patch_stream} tool so this parsing exists exactly once. */
	public static List<Segment> parseSegments(Object raw) {
		if ("flagged".equals(raw)) {
			return null;
		}
		if (!(raw instanceof List<?> list)) {
			throw new ValidationException("segments must be 'flagged' or a list of [start_t, end_t] pairs.", "segments");
		}
		List<Segment> segments = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof Integer)
					|| !(pair.get(1) instanceof Integer)) {
				throw new ValidationException("segments must be 'flagged' or a list of [start_t, end_t] pairs.", "segments");
			}
			segments.add(new Segment((Integer) pair.get(0), (Integer) pair.get(1)));
		}
		return segments;
	}

	/** {@code explicitSegments == null} means the caller passed {@code segments="flagged"} -
	 * calls the same detection GET .../streams/flags would, live, right now, using
	 * {@code kind}/{@code reference}, and patches whatever it currently finds - flags are never
	 * stored, matching {@link DropoutDetector}'s own "always recomputed, never persisted" stance
	 * (see {@link StreamPatch#getDetectedKind}'s own Javadoc). Explicit segments bypass
	 * detection entirely - the caller already knows what to patch. */
	private ResolvedSegments resolveSegments(String activityId, String field, List<Segment> explicitSegments,
			Kind kind, String reference) {
		List<Segment> segments;
		String detectedKind;
		if (explicitSegments == null) {
			if (kind == null) {
				throw new ValidationException("kind is required when segments='flagged'.", "kind");
			}
			StreamFlagsResult flags = streamFlagService.detectStreamFlags(activityId, field, kind, reference);
			segments = new ArrayList<>();
			for (FlagSegment flagSegment : flags.segments()) {
				segments.add(new Segment(flagSegment.startT(), flagSegment.endT()));
			}
			detectedKind = kind.wireValue();
		}
		else {
			segments = explicitSegments;
			detectedKind = null;
		}
		validateSegments(segments);
		return new ResolvedSegments(segments, detectedKind);
	}

	/** Code review follow-up on issue #540's own PR: closes the TOCTOU race between {@link
	 * #rejectOverlap}'s read and this method's own write - without this, two concurrent {@link
	 * #patchStream} calls on the same activity/field could both pass the overlap check before
	 * either committed, breaking the "unpatch is order-independent" invariant the overlap check
	 * exists to guarantee. A transaction-scoped Postgres advisory lock, keyed by a stable hash
	 * of (activityId, field) computed server-side (hashtextextended, not {@code
	 * String#hashCode()} - not guaranteed stable across JVMs/versions and so useless as a lock
	 * key), serializes concurrent callers on the same key without a schema change. Released
	 * automatically at commit or rollback. Must be called from inside the same transaction that
	 * does the overlap check and the write - {@link #patchStream} is {@code @Transactional} for
	 * its entire body, so this just needs to run before {@link #rejectOverlap}, not in any
	 * separately-opened transaction. */
	private void acquirePatchLock(String activityId, String field) {
		entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(?1, 0))")
				.setParameter(1, activityId + ":" + field)
				.getSingleResult();
	}

	/** A patch is rejected when its segments overlap any *active* ({@code undoneAt == null})
	 * StreamPatch on the same activity+field - without this, a second patch's previousValues
	 * would snapshot the first patch's already-patched values and unpatch order would matter
	 * (LIFO restores correctly; the reverse writes stale values over a still-active patch).
	 * Rejecting overlaps up front makes unpatch order-independent. */
	private void rejectOverlap(String activityId, String field, List<Segment> segments) {
		for (StreamPatch existing : streamPatchRepository.findByActivityIdAndField(activityId, field)) {
			if (existing.getUndoneAt() != null) {
				continue;
			}
			for (List<Integer> pair : existing.getSegments()) {
				int existingStart = pair.get(0);
				int existingEnd = pair.get(1);
				boolean overlaps = segments.stream()
						.anyMatch(s -> s.startT() <= existingEnd && existingStart <= s.endT());
				if (overlaps) {
					throw new ConflictException("Overlaps active patch '" + existing.getId() + "' on this activity/field.");
				}
			}
		}
	}

	private static boolean inAnySegment(int t, List<Segment> segments) {
		for (Segment segment : segments) {
			if (t >= segment.startT() && t <= segment.endT()) {
				return true;
			}
		}
		return false;
	}

	/** {@code explicitSegments} is null for {@code segments="flagged"} (kind/reference then
	 * required) - see {@link #resolveSegments}. {@code REQUIRES_NEW}, not the default {@code
	 * REQUIRED} - same reasoning as {@link ActivityMergeService#runMerge}'s own class Javadoc: a
	 * {@code preview=true} call throws {@link StreamPatchPreviewRollbackSignal} to force a
	 * rollback while still returning the already-computed result, and that signal must only
	 * ever roll back *this* call's own transaction, never join and poison some future caller's
	 * outer one. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public StreamPatchResult patchStream(String activityId, String field, String fromSourceId,
			List<Segment> explicitSegments, Kind kind, String reference, boolean preview) {
		ActivitySourceService.validateField(field);
		activityRepository.findById(activityId).orElseThrow(() -> new NotFoundException("No such activity."));
		ActivityStream sourceStream = streamRepository.findByActivityIdAndFieldAndSourceId(activityId, field, fromSourceId)
				.orElseThrow(() -> new NotFoundException("No such source stream for this activity/field."));

		ResolvedSegments resolved = resolveSegments(activityId, field, explicitSegments, kind, reference);
		acquirePatchLock(activityId, field);
		rejectOverlap(activityId, field, resolved.segments());

		List<Record> targetRows = recordRepository.findByActivityIdOrderByT(activityId);
		Map<Integer, Object> targetValues = new HashMap<>();
		for (Record row : targetRows) {
			targetValues.put(row.getT(), ActivityMergeApplyService.getRecordFieldValue(row, field));
		}
		Short[] sourceStreamValues = sourceStream.getStreamValues();
		int sourceT0 = sourceStream.getT0();
		Map<Integer, Object> sourceValues = new HashMap<>();
		for (int i = 0; i < sourceStreamValues.length; i++) {
			if (sourceStreamValues[i] != null) {
				sourceValues.put(sourceT0 + i, (int) sourceStreamValues[i]);
			}
		}

		Map.Entry<Map<Integer, Object>, FieldRewriteCounts> computed = ActivityMergeApplyService.computeStreamField(
				targetValues, sourceValues, 0, NULL_POLICY, null, resolved.segments());
		Map<Integer, Object> newValues = computed.getKey();
		FieldRewriteCounts counts = computed.getValue();

		Set<Integer> patchedTs = new HashSet<>();
		for (int t : targetValues.keySet()) {
			if (inAnySegment(t, resolved.segments())) {
				patchedTs.add(t);
			}
		}
		Map<String, Object> previousValues = new LinkedHashMap<>();
		for (int t : patchedTs) {
			previousValues.put(String.valueOf(t), targetValues.get(t));
		}

		List<Record> changedRows = new ArrayList<>();
		for (Record row : targetRows) {
			if (patchedTs.contains(row.getT())) {
				Object newValue = newValues.get(row.getT());
				if (!Objects.equals(ActivityMergeApplyService.getRecordFieldValue(row, field), newValue)) {
					ActivityMergeApplyService.setRecordFieldValue(row, field, newValue);
					changedRows.add(row);
				}
			}
		}
		if (!changedRows.isEmpty()) {
			recordRepository.saveAll(changedRows);
		}

		if (preview) {
			// Same reasoning as ActivityMergeService.runMerge's own preview: the lighter
			// activity-level-only recompute, not the full athlete-wide cascade, since a preview
			// only needs the target's own re-derived stats and the patch dialog fires one per
			// segment/source change.
			postProcessingService.recomputeActivityLevelOnly(activityId);
			throw new StreamPatchPreviewRollbackSignal(new StreamPatchResult(null, field, fromSourceId,
					resolved.detectedKind(), resolved.segments(), counts));
		}

		postProcessingService.recomputeAfterMerge(activityId);

		StreamPatch patch = new StreamPatch();
		patch.setActivity(sourceStream.getActivity());
		patch.setField(field);
		patch.setFromSource(sourceStream.getSource());
		patch.setDetectedKind(resolved.detectedKind());
		List<List<Integer>> segmentsJson = new ArrayList<>();
		for (Segment segment : resolved.segments()) {
			segmentsJson.add(List.of(segment.startT(), segment.endT()));
		}
		patch.setSegments(segmentsJson);
		patch.setPreviousValues(previousValues);
		patch = streamPatchRepository.save(patch);

		return new StreamPatchResult(patch.getId(), field, fromSourceId, resolved.detectedKind(), resolved.segments(),
				counts);
	}

	/** Mirrors {@code ActivityUnmergeService}'s exact ambiguity-handling shape: patchId picks a
	 * specific active patch directly; with no patchId, exactly one active patch on this activity
	 * must exist, or the request is ambiguous - blocked loudly (naming the available ids) rather
	 * than guessing which one was meant. */
	private StreamPatch activePatch(String activityId, String patchId) {
		if (patchId != null) {
			return streamPatchRepository.findById(patchId)
					.filter(p -> p.getActivity().getId().equals(activityId) && p.getUndoneAt() == null)
					.orElseThrow(() -> new UnprocessableEntityException("No active patch '" + patchId + "' found on this activity."));
		}
		List<StreamPatch> active = streamPatchRepository.findByActivityId(activityId).stream()
				.filter(p -> p.getUndoneAt() == null).toList();
		if (active.isEmpty()) {
			throw new UnprocessableEntityException("This activity has no active patch to undo.");
		}
		if (active.size() > 1) {
			String ids = active.stream().map(StreamPatch::getId).collect(Collectors.joining(", "));
			throw new UnprocessableEntityException(
					"This activity has more than one active patch (" + ids + ") - pass patch_id to choose which one to undo.");
		}
		return active.get(0);
	}

	private static Integer coerceToInteger(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalStateException("Expected a number in previousValues, got " + value.getClass());
	}

	/** Restores {@link Record}'s field values at the patch's segments from previousValues, sets
	 * undoneAt, and re-runs the recompute cascade. The {@link StreamPatch} row itself is kept
	 * (not deleted) - see {@link StreamPatch}'s own "accepted consequence" note on why this
	 * leaves fromSource permanently blocked from deletion even after undo. */
	@Transactional
	public StreamPatch unpatchStream(String activityId, String patchId) {
		StreamPatch patch = activePatch(activityId, patchId);
		List<Integer> patchedTs = patch.getPreviousValues().keySet().stream().map(Integer::parseInt).toList();
		Map<Integer, Record> rowsByT = new HashMap<>();
		for (Record row : recordRepository.findByActivityIdAndTIn(activityId, patchedTs)) {
			rowsByT.put(row.getT(), row);
		}

		List<Record> changedRows = new ArrayList<>();
		for (Map.Entry<String, Object> entry : patch.getPreviousValues().entrySet()) {
			int t = Integer.parseInt(entry.getKey());
			Record row = rowsByT.get(t);
			if (row == null) {
				continue;
			}
			Integer originalValue = coerceToInteger(entry.getValue());
			if (!Objects.equals(ActivityMergeApplyService.getRecordFieldValue(row, patch.getField()), originalValue)) {
				ActivityMergeApplyService.setRecordFieldValue(row, patch.getField(), originalValue);
				changedRows.add(row);
			}
		}
		if (!changedRows.isEmpty()) {
			recordRepository.saveAll(changedRows);
		}
		patch.setUndoneAt(Instant.now());
		patch = streamPatchRepository.save(patch);
		postProcessingService.recomputeAfterMerge(activityId);
		return patch;
	}
}
