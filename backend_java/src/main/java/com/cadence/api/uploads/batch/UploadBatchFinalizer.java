package com.cadence.api.uploads.batch;

import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.OverlapAction;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadBatchRepository;
import com.cadence.api.uploads.UploadBatchStatus;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.webhooks.UploadBatchCompletedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks an {@link UploadBatch} completed once none of its uploads are still queued or
 * processing. Called after every parse job finishes, and once right after staging - a
 * batch whose files all short-circuit at staging (e.g. every one a duplicate) never
 * launches a job, so no job completion would ever settle it.
 */
@Component
public class UploadBatchFinalizer {

	private final UploadRepository uploadRepository;
	private final UploadBatchRepository uploadBatchRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final ActivityService activityService;

	public UploadBatchFinalizer(UploadRepository uploadRepository, UploadBatchRepository uploadBatchRepository,
			ApplicationEventPublisher eventPublisher, ActivityService activityService) {
		this.uploadRepository = uploadRepository;
		this.uploadBatchRepository = uploadBatchRepository;
		this.eventPublisher = eventPublisher;
		this.activityService = activityService;
	}

	@Transactional
	public void maybeFinalize(String batchId) {
		long pending = uploadRepository.countByBatchIdAndStatusIn(batchId, List.of(UploadStatus.QUEUED, UploadStatus.PROCESSING));
		if (pending > 0) {
			return;
		}
		UploadBatch batch = uploadBatchRepository.findById(batchId).orElse(null);
		if (batch == null || batch.getStatus() == UploadBatchStatus.COMPLETED) {
			return;
		}
		// Issue #498: runs once for the whole batch, rather than once per marked file (see
		// ParseFileTasklet's own call site, skipped for a batch child precisely so this is the
		// one place it happens).
		if (uploadRepository.existsByBatchIdAndOverlapAction(batchId, OverlapAction.MARKED)) {
			// Issue #545: one recompute from the earliest marked upload's activity date - a
			// duplicate-status change can only alter the ledger from that date onward. A null
			// (no marked upload has an activity - shouldn't happen) falls back to the full
			// rebuild inside recomputeAfterDuplicateChange.
			LocalDate earliestChanged = uploadRepository.findByBatchIdWithActivity(batchId).stream()
					.filter(u -> u.getOverlapAction() == OverlapAction.MARKED && u.getActivity() != null)
					.map(u -> u.getActivity().getStartDate().atZone(ZoneOffset.UTC).toLocalDate())
					.min(LocalDate::compareTo)
					.orElse(null);
			activityService.recomputeAfterDuplicateChange(batch.getAthlete(), java.util.EnumSet.allOf(ThresholdField.class), earliestChanged);
		}
		batch.setStatus(UploadBatchStatus.COMPLETED);
		batch.setCompletedAt(Instant.now());
		uploadBatchRepository.save(batch);
		eventPublisher.publishEvent(new UploadBatchCompletedEvent(batch.getId(), batch.getAthlete().getId()));
	}
}
