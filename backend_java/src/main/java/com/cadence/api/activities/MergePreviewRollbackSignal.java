package com.cadence.api.activities;

/** Thrown by {@link ActivityMergeService#runMerge} to force a rollback of a {@code preview=true}
 * POST .../merge while still returning the already-computed {@link ActivityMergeService.MergeResult}
 * to the caller. Same technique as athletes' {@code PreviewRollbackSignal} - see its own Javadoc
 * for why a nested participant can't just call {@code TransactionStatus.setRollbackOnly()}
 * itself: {@code runMerge}'s own {@code @Transactional} is the real transaction, so the signal is
 * thrown from runMerge itself, after every step has run, and caught one level out (in
 * ActivityMergeController, which isn't itself transactional) to recover the payload. */
final class MergePreviewRollbackSignal extends RuntimeException {

	private final ActivityMergeService.MergeResult result;

	MergePreviewRollbackSignal(ActivityMergeService.MergeResult result) {
		super(null, null, false, false);
		this.result = result;
	}

	ActivityMergeService.MergeResult result() {
		return result;
	}
}
