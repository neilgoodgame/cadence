package com.cadence.api.activities;

/** Thrown by {@link ActivityStreamPatchService#patchStream} to force a rollback of a {@code
 * preview=true} POST .../streams/patch while still returning the already-computed {@link
 * ActivityStreamPatchService.StreamPatchResult} to the caller. Same technique as {@link
 * MergePreviewRollbackSignal} - see its own Javadoc for why a nested participant can't just call
 * {@code TransactionStatus.setRollbackOnly()} itself: {@code patchStream}'s own {@code
 * @Transactional} is the real transaction, so the signal is thrown from inside it, after every
 * step has run, and caught one level out (in the controller, and in the MCP write tool) to
 * recover the payload. */
public final class StreamPatchPreviewRollbackSignal extends RuntimeException {

	private final ActivityStreamPatchService.StreamPatchResult result;

	StreamPatchPreviewRollbackSignal(ActivityStreamPatchService.StreamPatchResult result) {
		super(null, null, false, false);
		this.result = result;
	}

	public ActivityStreamPatchService.StreamPatchResult result() {
		return result;
	}
}
