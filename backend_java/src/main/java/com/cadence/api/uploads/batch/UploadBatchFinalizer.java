package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadBatchRepository;
import com.cadence.api.uploads.UploadBatchStatus;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.webhooks.UploadBatchCompletedEvent;
import java.time.Instant;
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

	public UploadBatchFinalizer(UploadRepository uploadRepository, UploadBatchRepository uploadBatchRepository,
			ApplicationEventPublisher eventPublisher) {
		this.uploadRepository = uploadRepository;
		this.uploadBatchRepository = uploadBatchRepository;
		this.eventPublisher = eventPublisher;
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
		batch.setStatus(UploadBatchStatus.COMPLETED);
		batch.setCompletedAt(Instant.now());
		uploadBatchRepository.save(batch);
		eventPublisher.publishEvent(new UploadBatchCompletedEvent(batch.getId(), batch.getAthlete().getId()));
	}
}
