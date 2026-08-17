package com.cadence.api.users;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window per-IP limit on {@code POST /v1/auth/register}, in memory - correct
 * because the API runs as a single instance (see infra/EC2_BACKEND_SKETCH.md), not a
 * fleet where separate instances would each keep their own count. Deliberately not
 * backed by Redis/a shared store for the same reason: nothing to share state with.
 * Only guards registration; login/token endpoints aren't in scope here.
 */
@Component
public class RegistrationRateLimiter {

	private final int maxAttempts;
	private final Duration window;
	private final Clock clock;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	// Explicit @Autowired: with two constructors present, Spring won't infer which one to
	// use and falls back to looking for a no-arg constructor - "No default constructor
	// found" at boot otherwise. Found by actually booting the container, not just tests.
	@Autowired
	public RegistrationRateLimiter(CadenceProperties properties) {
		this(properties, Clock.systemUTC());
	}

	RegistrationRateLimiter(CadenceProperties properties, Clock clock) {
		this.maxAttempts = properties.rateLimit().registerMaxAttempts();
		this.window = Duration.ofMinutes(properties.rateLimit().registerWindowMinutes());
		this.clock = clock;
	}

	/** @throws ApiException 429 if this IP has exceeded the limit for the current window. */
	public void checkAndRecord(String clientIp) {
		Instant now = clock.instant();
		Window w = windows.compute(clientIp, (ip, existing) -> {
			if (existing == null || now.isAfter(existing.expiresAt())) {
				return new Window(now.plus(window), new AtomicInteger(1));
			}
			existing.count().incrementAndGet();
			return existing;
		});
		if (w.count().get() > maxAttempts) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many registration attempts from this address - try again later.", null);
		}
	}

	// Windows expire on their own (checkAndRecord replaces an expired one on next use),
	// but without this, an IP that registers once and never returns leaves its entry in
	// the map forever - unbounded growth on a long-running instance otherwise.
	@Scheduled(fixedRate = 30, timeUnit = TimeUnit.MINUTES)
	void evictExpired() {
		Instant now = clock.instant();
		windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
	}

	private record Window(Instant expiresAt, AtomicInteger count) {
	}
}
