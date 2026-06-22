package com.cadence.api.activities.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record StreamsResponse(String resolution, Map<String, Object> fields) {

	@JsonProperty("object")
	public String object() {
		return "streams";
	}
}
