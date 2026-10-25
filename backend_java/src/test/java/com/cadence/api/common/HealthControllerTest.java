package com.cadence.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

	@Test
	void reportsStatusAndBuild() {
		HealthController controller = new HealthController("sha-abc1234");

		assertThat(controller.healthcheck()).isEqualTo(Map.of("status", "ok", "build", "sha-abc1234"));
	}

	@Test
	void defaultsBuildToUnknownWhenNotConfigured() {
		HealthController controller = new HealthController("unknown");

		assertThat(controller.healthcheck()).isEqualTo(Map.of("status", "ok", "build", "unknown"));
	}
}
