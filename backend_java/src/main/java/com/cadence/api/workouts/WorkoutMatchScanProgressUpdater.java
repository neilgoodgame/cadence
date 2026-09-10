package com.cadence.api.workouts;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists every {@code WorkoutMatchScan} state transition in its own transaction, independent
 * of whatever transaction (if any) is active on the calling thread - mirrors {@code
 * ExportProgressUpdater}'s {@code REQUIRES_NEW} technique, but goes one step further and covers
 * every mutation (including the terminal status/error/completedAt fields), not just the
 * in-flight progress counters. {@code WorkoutMatchScanService.runScanSync} never holds or saves
 * its own {@code WorkoutMatchScan} Java reference across these calls - each method here
 * re-fetches fresh and only mutates the one field (or handful of fields) it owns. That matters:
 * a plain {@code scanRepository.save(staleScanObject)} at the very end of a long-running method
 * would merge the WHOLE entity state back in, silently clobbering every field this updater
 * wrote out-of-band in the meantime (e.g. resetting processedCandidates back to whatever it was
 * when the method's local reference was first loaded, minutes earlier) - caught by this
 * feature's own test before it shipped, not a hypothetical.
 */
@Service
public class WorkoutMatchScanProgressUpdater {

	private final WorkoutMatchScanRepository scanRepository;

	public WorkoutMatchScanProgressUpdater(WorkoutMatchScanRepository scanRepository) {
		this.scanRepository = scanRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markProcessing(String scanId) {
		scanRepository.findById(scanId).ifPresent(s -> s.setStatus(WorkoutMatchScanStatus.PROCESSING));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateTotalCandidates(String scanId, int total) {
		scanRepository.findById(scanId).ifPresent(s -> s.setTotalCandidates(total));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProcessedCandidates(String scanId, int processed) {
		scanRepository.findById(scanId).ifPresent(s -> s.setProcessedCandidates(processed));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markReady(String scanId, Instant completedAt) {
		scanRepository.findById(scanId).ifPresent(s -> {
			s.setStatus(WorkoutMatchScanStatus.READY);
			s.setCompletedAt(completedAt);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(String scanId, String errorMessage, Instant completedAt) {
		scanRepository.findById(scanId).ifPresent(s -> {
			s.setStatus(WorkoutMatchScanStatus.FAILED);
			s.setErrorMessage(errorMessage);
			s.setCompletedAt(completedAt);
		});
	}
}
