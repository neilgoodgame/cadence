package com.cadence.api.common;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a long-running recompute SSE connection provably alive from the client's perspective,
 * independent of how long the real work between progress updates happens to take. Without
 * this, a client-side stall-timeout has to choose between "tight enough to catch a genuinely
 * dead connection quickly" and "loose enough to tolerate one unusually slow item" - and no
 * fixed value is safe against an item slow enough to exceed it, however generous. Seen for
 * real: a legitimately-running recompute (confirmed alive via sustained CPU the whole time,
 * zero server-side errors) was killed client-side because one gap between progress events
 * happened to exceed the 60s stall timeout (see infra/README.md's Step 18).
 *
 * <p>Sends a trivial named "heartbeat" event on a fixed interval, unrelated to real progress.
 * The client's stall timeout only ever needs to be longer than this interval, never longer
 * than any single item's worst-case processing time.
 */
public final class SseHeartbeat implements AutoCloseable {

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	/** Unlike {@link Runnable}, allowed to throw - {@code SseEmitter#send} declares a checked
	 * {@code IOException}. */
	@FunctionalInterface
	public interface Send {
		void run() throws Exception;
	}

	/** {@code send} is typically {@code () -> emitter.send(SseEmitter.event().name("heartbeat")
	 * .data("{}"))} - taken as a callback rather than an SseEmitter directly so this class has
	 * no framework dependency and is trivially unit-testable. */
	public SseHeartbeat(Send send, long intervalSeconds) {
		executor.scheduleAtFixedRate(() -> {
			try {
				send.run();
			}
			catch (Exception e) {
				// The emitter is already completed or disconnected - the caller's own
				// completion path (success/error) is responsible for calling close() on this;
				// nothing to do here except let this scheduled run be a no-op.
			}
		}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
	}

	/** Must be called once the real work finishes (success or error) - typically from a
	 * finally block, since this doesn't stop on its own. */
	@Override
	public void close() {
		executor.shutdownNow();
	}
}
