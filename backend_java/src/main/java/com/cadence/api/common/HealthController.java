package com.cadence.api.common;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	// Baked into the image at build time via the Dockerfile's GIT_SHA build arg -
	// infra/scripts/deploy-backend.sh passes the exact commit it's deploying, so this answers
	// "what's actually running right now" without guessing from the deploy log.
	private final String build;

	public HealthController(@Value("${BUILD_SHA:unknown}") String build) {
		this.build = build;
	}

	@GetMapping("/healthz")
	public Map<String, String> healthcheck() {
		return Map.of("status", "ok", "build", build);
	}
}
