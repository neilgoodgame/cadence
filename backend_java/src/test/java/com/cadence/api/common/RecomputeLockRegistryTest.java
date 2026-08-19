package com.cadence.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecomputeLockRegistryTest {

	@Test
	void secondAcquireForTheSameKindAndAthleteFailsWhileTheFirstIsHeld() {
		RecomputeLockRegistry registry = new RecomputeLockRegistry();

		assertThat(registry.tryAcquire("best-efforts", "usr_1")).isTrue();
		assertThat(registry.tryAcquire("best-efforts", "usr_1")).isFalse();
	}

	@Test
	void releasingFreesTheLockForAFutureAcquire() {
		RecomputeLockRegistry registry = new RecomputeLockRegistry();
		registry.tryAcquire("best-efforts", "usr_1");

		registry.release("best-efforts", "usr_1");

		assertThat(registry.tryAcquire("best-efforts", "usr_1")).isTrue();
	}

	@Test
	void differentKindsForTheSameAthleteDontInterfere() {
		RecomputeLockRegistry registry = new RecomputeLockRegistry();

		assertThat(registry.tryAcquire("best-efforts", "usr_1")).isTrue();
		assertThat(registry.tryAcquire("curves", "usr_1")).isTrue();
	}

	@Test
	void theSameKindForDifferentAthletesDontInterfere() {
		RecomputeLockRegistry registry = new RecomputeLockRegistry();

		assertThat(registry.tryAcquire("best-efforts", "usr_1")).isTrue();
		assertThat(registry.tryAcquire("best-efforts", "usr_2")).isTrue();
	}
}
