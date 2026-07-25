package com.cadence.api.common;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	private final JdbcTemplate jdbc;

	public HealthController(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@GetMapping("/healthz")
	public ResponseEntity<Map<String, Object>> healthcheck() {
		Map<String, Object> body = new LinkedHashMap<>();
		String dbStatus;
		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
			dbStatus = "ok";
		}
		catch (Exception e) {
			dbStatus = "error: " + e.getMessage();
		}

		boolean healthy = "ok".equals(dbStatus);
		body.put("status", healthy ? "ok" : "degraded");
		body.put("db", dbStatus);

		return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
	}
}
