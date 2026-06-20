package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadBatchRepository;
import com.cadence.api.uploads.UploadBatchStatus;
import com.cadence.api.uploads.UploadProcessingException;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.webhooks.UploadBatchCompletedEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets the terminal {@link Upload} status whether the job succeeded or failed, and - regardless
 * of outcome - checks whether the parent {@link UploadBatch} (if any) is now fully settled.
 * Registered once on the {@code Job}, not per step, so it runs exactly once per file.
 */
@Component
public class UploadJobExecutionListener implements JobExecutionListener {

	private final UploadRepository uploadRepository;
	private final UploadBatchRepository uploadBatchRepository;
	private final ApplicationEventPublisher eventPublisher;

	public UploadJobExecutionListener(UploadRepository uploadRepository, UploadBatchRepository uploadBatchRepository,
			ApplicationEventPublisher eventPublisher) {
		this.uploadRepository = uploadRepository;
		this.uploadBatchRepository = uploadBatchRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public void afterJob(JobExecution jobExecution) {
		String uploadId = jobExecution.getJobParameters().getString("uploadId");
		if (uploadId == null) {
			return;
		}
		Upload upload = uploadRepository.findById(uploadId).orElse(null);
		if (upload == null) {
			return;
		}

		if (jobExecution.getStatus() == BatchStatus.FAILED) {
			Throwable cause = jobExecution.getAllFailureExceptions().stream().findFirst().orElse(null);
			upload.setStatus(UploadStatus.FAILED);
			if (cause instanceof UploadProcessingException upe) {
				upload.setErrorCode(upe.getCode());
				upload.setErrorMessage(upe.getMessage());
			}
			else {
				upload.setErrorCode("corrupt_file");
				upload.setErrorMessage(cause != null ? cause.getMessage() : "Unknown error.");
			}
			upload.setCompletedAt(Instant.now());
			uploadRepository.save(upload);
		}

		if (upload.getBatch() != null) {
			maybeFinalizeBatch(upload.getBatch().getId());
		}
	}

	private void maybeFinalizeBatch(String batchId) {
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
