package com.cadence.api.gear.dto;

import jakarta.validation.constraints.NotBlank;

public record ShoeCreateRequest(
		@NotBlank String shoeModelVersionId, @NotBlank String colourway, String name, Integer limitKm, String image) {
}
