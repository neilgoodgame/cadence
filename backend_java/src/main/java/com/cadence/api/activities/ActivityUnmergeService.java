package com.cadence.api.activities;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.parsing.FileParserDispatcher;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #501 (phase 1, part 5): undoes a merge - restores the target's original field values and
 * un-hides the source if the merge hid it.
 *
 * <p>Stream fields are restored by re-parsing the target's own stored upload file - the exact
 * file ingest itself parsed, so its samples are the authoritative "what this activity originally
 * recorded" and share the target's own timeline exactly (no alignment/offset needed, unlike a
 * merge itself). Activity-level fields are restored from the snapshot
 * {@link ActivityMergeService#runMerge} took immediately before overwriting them - re-deriving
 * them instead (e.g. regenerating {@code name} from sport+date) would silently discard any
 * legitimate edit made between the merge and this undo.
 *
 * <p>Disallowed when the target's upload row has no stored file (legacy data, or an export
 * restore with no original file) - the same open question #501 itself resolved. Mirrors
 * backend's activities/unmerge_service.py - keep the two in sync when changing either.
 */
@Service
public class ActivityUnmergeService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final MergeRepository mergeRepository;
	private final UploadRepository uploadRepository;
	private final BikeRepository bikeRepository;
	private final ShoeRepository shoeRepository;
	private final ActivityService activityService;
	private final ActivityMergePostProcessingService postProcessingService;
	private final CadenceProperties properties;

	public ActivityUnmergeService(ActivityRepository activityRepository, RecordRepository recordRepository,
			MergeRepository mergeRepository, UploadRepository uploadRepository, BikeRepository bikeRepository,
			ShoeRepository shoeRepository, ActivityService activityService,
			ActivityMergePostProcessingService postProcessingService, CadenceProperties properties) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.mergeRepository = mergeRepository;
		this.uploadRepository = uploadRepository;
		this.bikeRepository = bikeRepository;
		this.shoeRepository = shoeRepository;
		this.activityService = activityService;
		this.postProcessingService = postProcessingService;
		this.properties = properties;
	}

	/** Restoring stream fields re-parses the target's *original* upload file - correct for a
	 * single merge, but wrong for a chain of two-or-more merges that touched the same field
	 * (undoing the second should land on the first merge's result, not on the pre-every-merge
	 * original). Since nothing here tracks per-merge snapshots of stream data (that's the whole
	 * reason undo re-parses rather than storing them), a target with more than one active merge
	 * can't be safely undone at all - block it loudly rather than silently restoring too far
	 * back. */
	private Merge latestActiveMerge(String targetId) {
		List<Merge> active = mergeRepository.findByTargetActivityId(targetId).stream()
				.filter(m -> m.getUndoneAt() == null).toList();
		if (active.isEmpty()) {
			throw new UnprocessableEntityException("This activity has no active merge to undo.");
		}
		if (active.size() > 1) {
			throw new UnprocessableEntityException(
					"This activity has more than one merge applied - undo isn't supported for a merge chain.");
		}
		return active.get(0);
	}

	private Upload resolveUpload(String targetId) {
		Upload upload = uploadRepository.findByActivityId(targetId).orElse(null);
		if (upload == null || upload.getStoredPath() == null || upload.getStoredPath().isBlank()) {
			throw new UnprocessableEntityException("This merge can't be undone: no original file stored.");
		}
		return upload;
	}

	private Object sampleFieldValue(ParsedActivity.Sample sample, String field) {
		return switch (field) {
			case "power" -> sample.power();
			case "heartrate" -> sample.heartrate();
			case "cadence" -> sample.cadence();
			case "altitude" -> sample.altitude();
			case "lat" -> sample.lat();
			case "lng" -> sample.lng();
			case "speed" -> sample.speed();
			case "distance_km" -> sample.distanceKm();
			case "air_temp" -> sample.airTemp();
			case "humidity" -> sample.humidity();
			case "skin_temp" -> sample.skinTemp();
			case "core_temp" -> sample.coreTemp();
			case "heat_strain" -> sample.heatStrain();
			default -> null;
		};
	}

	/** {@code REQUIRES_NEW}, not the default {@code REQUIRED} - see {@link
	 * ActivityMergeService#runMerge}'s own Javadoc for the full reasoning (issue #501 phase 4's
	 * {@code DuplicateResolutionBatchService#undoBatch} calls this from inside its own
	 * already-{@code @Transactional} method and catches {@link UnprocessableEntityException}
	 * per entry to skip just that one; with plain {@code REQUIRED} the exception would still
	 * mark undoBatch's *whole* transaction rollback-only on its way up, even though it's caught
	 * - every existing caller (the REST controller, the MCP tool) is already non-transactional,
	 * so this changes nothing for them). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Merge undoMerge(String targetId) {
		Activity target = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		Merge merge = latestActiveMerge(targetId);
		Upload upload = resolveUpload(targetId);

		List<ParsedActivity> parsedActivities;
		// ingestUpload links Upload.activity only to the parent/sole activity, never a
		// multisport leg (see its own Javadoc) - a target reachable past resolveUpload above
		// can never BE one, so parsedActivities.get(0) (the parent/sole parse result) is
		// always the right one.
		Path path = Path.of(properties.uploads().mediaRoot(), upload.getStoredPath());
		try (InputStream in = Files.newInputStream(path)) {
			parsedActivities = FileParserDispatcher.parse(in, upload.getFilename());
		}
		catch (Exception e) {
			throw new UnprocessableEntityException("This merge can't be undone: the original file couldn't be re-read.");
		}
		Map<Integer, ParsedActivity.Sample> samplesByT = new HashMap<>();
		for (ParsedActivity.Sample sample : parsedActivities.get(0).samples()) {
			samplesByT.put(sample.t(), sample);
		}

		List<String> streamFields = merge.getFields().stream().filter(ActivityMergeApplyService::isStreamField).toList();
		List<String> activityFields = merge.getFields().stream()
				.filter(ActivityMergeApplyService::isActivityLevelField).toList();

		if (!streamFields.isEmpty()) {
			List<Record> targetRows = recordRepository.findByActivityIdOrderByT(targetId);
			List<Record> changedRows = new ArrayList<>();
			for (Record row : targetRows) {
				ParsedActivity.Sample original = samplesByT.get(row.getT());
				for (String field : streamFields) {
					Object originalValue = original == null ? null : sampleFieldValue(original, field);
					ActivityMergeApplyService.setRecordFieldValue(row, field, originalValue);
				}
				changedRows.add(row);
			}
			recordRepository.saveAll(changedRows);
		}

		if (!activityFields.isEmpty()) {
			Map<String, Object> previous = merge.getPreviousActivityValues();
			for (String field : activityFields) {
				Object value = previous.get(field);
				if (field.equals("bike_id")) {
					value = value == null ? null : bikeRepository.findById((String) value).orElse(null);
				}
				else if (field.equals("shoe_id")) {
					value = value == null ? null : shoeRepository.findById((String) value).orElse(null);
				}
				ActivityMergeApplyService.setActivityFieldValue(target, field, value);
			}
			activityRepository.save(target);
		}

		Activity source = null;
		if (merge.isSourceHidden() && merge.getSourceActivity() != null) {
			Activity candidate = activityRepository.findById(merge.getSourceActivity().getId()).orElse(null);
			// Only un-hide if it's still pointing at this exact target - if something else
			// re-resolved it since (a later mark/unmark/different merge), leave it alone rather
			// than clobber that newer state, the same caution undoBatch's own primary-check
			// already applies.
			if (candidate != null && candidate.getPrimaryActivity() != null
					&& candidate.getPrimaryActivity().getId().equals(target.getId())) {
				candidate.setPrimaryActivity(null);
				activityRepository.save(candidate);
				activityService.applyDuplicateGearMileageChange(candidate, true, false);
				source = candidate;
			}
		}

		postProcessingService.recomputeAfterMerge(targetId);
		if (source != null) {
			// recomputeAfterMerge(targetId) already reran the full-athlete threshold
			// ledger/TSS cascade, which now sees source un-excluded - but duration curve/best
			// efforts/workout-match are per-activity and were never run for source itself.
			postProcessingService.recomputeAfterMerge(source.getId());
		}

		merge.setUndoneAt(Instant.now());
		return mergeRepository.save(merge);
	}
}
