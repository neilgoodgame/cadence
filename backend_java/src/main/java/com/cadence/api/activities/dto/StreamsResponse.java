package com.cadence.api.activities.dto;

import java.util.Map;

public record StreamsResponse(String resolution, Map<String, Object> fields) {

	public String object() {
		return "streams";
	}
}
