package com.cadence.api.common;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Prevents two concurrent long-running recompute operations of the same kind for the same
 * athlete. Best-efforts recompute deletes then rewrites rows by id (see
 * BestEffortRecomputeService) - two overlapping runs for the same athlete race on the same
 * delete, and one loses with a Hibernate StaleStateException. Seen for real in production: an
 * athlete's earlier recompute was still genuinely running server-side (a client disconnecting
 * doesn't stop it - the async task isn't tied to the SSE connection's lifecycle) when they
 * started a second one, and the two collided (see infra/README.md's Step 17).
 *
 * <p>In-memory, not a distributed lock - correct because this API runs as a single instance,
 * not a fleet (see infra/EC2_BACKEND_SKETCH.md), same reasoning as RegistrationRateLimiter.
 */
@Component
public class RecomputeLockRegistry {

	private final Set<String> running = ConcurrentHashMap.newKeySet();

	/** @return true if the lock was acquired - no recompute of this kind was already running for this athlete. */
	public boolean tryAcquire(String kind, String athleteId) {
		return running.add(key(kind, athleteId));
	}

	/** Callers must release from a finally block regardless of how the recompute ended. */
	public void release(String kind, String athleteId) {
		running.remove(key(kind, athleteId));
	}

	private static String key(String kind, String athleteId) {
		return kind + ":" + athleteId;
	}
}
