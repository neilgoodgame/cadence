package com.cadence.api.export;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists ExportJob.currentStep in its own transaction, independent of whatever transaction
 * (if any) is active on the calling thread. {@link ExportWriter#write} runs inside its own
 * read-only transaction, so a plain save from within its onStep callback would just join that
 * transaction and stay invisible to a polling GET /v1/export/{id} request until the whole
 * export finishes - defeating the point of a progress update. REQUIRES_NEW suspends whatever
 * transaction is active and commits this one immediately on its own. Kept as a separate bean
 * (not a method on ExportWriter/ExportService) so the propagation actually applies - Spring's
 * transactional proxying doesn't intercept a bean calling its own method.
 */
@Service
public class ExportProgressUpdater {

	private final ExportJobRepository exportJobRepository;

	public ExportProgressUpdater(ExportJobRepository exportJobRepository) {
		this.exportJobRepository = exportJobRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateStep(String jobId, String step) {
		exportJobRepository.findById(jobId).ifPresent(job -> job.setCurrentStep(step));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateTotal(String jobId, int total) {
		exportJobRepository.findById(jobId).ifPresent(job -> job.setTotalItems(total));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(String jobId, int processed) {
		exportJobRepository.findById(jobId).ifPresent(job -> job.setProcessedItems(processed));
	}
}
