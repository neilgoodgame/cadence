package com.cadence.api.activities.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

public record TagAttachRequest(String tagId, String name) {

	@JsonIgnore
	@AssertTrue(message = "Provide either tag_id or name.")
	public boolean isShapeValid() {
		return (tagId != null && !tagId.isBlank()) || (name != null && !name.isBlank());
	}
}
