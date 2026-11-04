package com.cadence.api.athletes;

/** Thrown by {@link AthleteService#updateProfile} to force a rollback of a {@code preview=true}
 * PATCH /v1/athletes/{id} (issue #493) while still returning the already-computed
 * {@link AthleteService.Outcome} to the caller.
 *
 * <p>Marking the transaction rollback-only (via {@code TransactionStatus.setRollbackOnly()}) and
 * then returning normally doesn't work here: updateProfile's own {@code @Transactional} is the
 * real, new transaction for the whole HTTP request, while any threshold field write happens via
 * {@code ThresholdHistoryService.recordManualValue}, a nested participant in that same
 * transaction (Spring's default REQUIRED propagation). A nested participant marking
 * rollback-only sets the shared transaction's global flag, not a local one scoped to its own
 * call - so when the owning (outer) transaction then tries to commit normally, Spring finds the
 * mismatch and throws {@code UnexpectedRollbackException} instead of returning anything.
 * Throwing this from updateProfile itself - after all submitted fields have been processed, so
 * a multi-field preview request isn't cut short after the first one - lets Spring's ordinary
 * exception-triggered rollback run instead (no commit is ever attempted, so no mismatch).
 * {@link AthleteController}, one step further out and not itself transactional, catches it to
 * recover the payload. */
final class PreviewRollbackSignal extends RuntimeException {

	private final AthleteService.Outcome outcome;

	PreviewRollbackSignal(AthleteService.Outcome outcome) {
		super(null, null, false, false);
		this.outcome = outcome;
	}

	AthleteService.Outcome outcome() {
		return outcome;
	}
}
