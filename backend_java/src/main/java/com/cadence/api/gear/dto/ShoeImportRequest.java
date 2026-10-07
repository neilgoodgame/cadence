package com.cadence.api.gear.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ShoeImportRequest(@NotEmpty @Valid List<Entry> entries) {

	public record Entry(
			@NotBlank @Size(max = 150) String manufacturer,
			@NotBlank @Size(max = 150) String model,
			@Size(max = 50) String version,
			@Size(max = 150) String colourway,
			Double distanceKm) {
	}
}
