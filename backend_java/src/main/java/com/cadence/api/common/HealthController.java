package com.cadence.api.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	@GetMapping("/healthz")
	public Map<String, String> healthcheck() {
		return Map.of("status", "ok");
	}
}
