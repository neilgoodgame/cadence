package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadProcessingException;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import java.time.Instant;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
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
	private final UploadBatchFinalizer batchFinalizer;

	public UploadJobExecutionListener(UploadRepository uploadRepository, UploadBatchFinalizer batchFinalizer) {
		this.uploadRepository = uploadRepository;
		this.batchFinalizer = batchFinalizer;
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
			// Garmin account exports mix metadata-stub FITs (no activity data) in with real
			// activities; failing them would drown a bulk import in errors, so batch children
			// are skipped instead. A deliberate single-file upload still fails loudly.
			if (cause instanceof UploadProcessingException upe && "no_activity_data".equals(upe.getCode())
					&& upload.getBatch() != null) {
				upload.setStatus(UploadStatus.SKIPPED);
			}
			else {
				upload.setStatus(UploadStatus.FAILED);
				if (cause instanceof UploadProcessingException upe) {
					upload.setErrorCode(upe.getCode());
					upload.setErrorMessage(upe.getMessage());
				}
				else {
					upload.setErrorCode("corrupt_file");
					upload.setErrorMessage(cause != null ? cause.getMessage() : "Unknown error.");
				}
			}
			upload.setCompletedAt(Instant.now());
			uploadRepository.save(upload);
		}

		if (upload.getBatch() != null) {
			batchFinalizer.maybeFinalize(upload.getBatch().getId());
		}
	}
}
