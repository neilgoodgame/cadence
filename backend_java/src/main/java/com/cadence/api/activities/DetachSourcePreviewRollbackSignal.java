package com.cadence.api.activities;

/** Thrown by {@link ActivitySourceService#detachSource} to force a rollback of a {@code
 * preview=true} detach while still returning the already-computed {@code needsRestore} result to
 * the caller. Same technique as {@link MergePreviewRollbackSignal} - see its own Javadoc for why
 * a nested participant can't just call {@code TransactionStatus.setRollbackOnly()} itself:
 * {@code detachSource}'s own {@code @Transactional} is the real transaction, so the signal is
 * thrown from inside it, after every step has run, and caught one level out to recover the
 * payload - in {@code ActivityController} (same package) and in the MCP write tool's {@code
 * detachSource} (a different package, unlike {@code MergePreviewRollbackSignal}'s callers, which
 * all go through the same-package {@code ActivityMergeOrchestrator} - there's no orchestrator
 * here, so this class is public specifically so that caller can catch it directly too). */
public final class DetachSourcePreviewRollbackSignal extends RuntimeException {

	private final boolean needsRestore;

	DetachSourcePreviewRollbackSignal(boolean needsRestore) {
		super(null, null, false, false);
		this.needsRestore = needsRestore;
	}

	public boolean needsRestore() {
		return needsRestore;
	}
}
