package com.cadence.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SseHeartbeatTest {

	/** Polls up to {@code timeoutMs}, sleeping 50ms between checks, rather than a single fixed
	 * sleep - keeps the test fast on a quick machine without being flaky on a slow one. */
	private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Condition not met within " + timeoutMs + "ms");
			}
			Thread.sleep(50);
		}
	}

	@Test
	void firesRepeatedlyOnTheGivenInterval() throws InterruptedException {
		AtomicInteger fires = new AtomicInteger();
		SseHeartbeat heartbeat = new SseHeartbeat(fires::incrementAndGet, 1);

		try {
			waitUntil(() -> fires.get() >= 2, 4000);
		}
		finally {
			heartbeat.close();
		}
	}

	@Test
	void stopsFiringOnceClosed() throws InterruptedException {
		AtomicInteger fires = new AtomicInteger();
		SseHeartbeat heartbeat = new SseHeartbeat(fires::incrementAndGet, 1);

		waitUntil(() -> fires.get() >= 1, 3000);
		heartbeat.close();
		int countAtClose = fires.get();
		Thread.sleep(2500);

		assertThat(fires.get()).isEqualTo(countAtClose);
	}

	@Test
	void anExceptionFromOneFireDoesNotStopSubsequentOnes() throws InterruptedException {
		AtomicInteger fires = new AtomicInteger();
		SseHeartbeat heartbeat = new SseHeartbeat(() -> {
			fires.incrementAndGet();
			throw new java.io.IOException("simulated disconnected client");
		}, 1);

		try {
			waitUntil(() -> fires.get() >= 2, 4000);
		}
		finally {
			heartbeat.close();
		}
	}
}
