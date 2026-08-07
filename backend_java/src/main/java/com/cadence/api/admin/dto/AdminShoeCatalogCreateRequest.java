package com.cadence.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminShoeCatalogCreateRequest(
		@NotBlank @Size(max = 150) String manufacturer,
		@NotBlank @Size(max = 150) String model,
		@NotBlank @Size(max = 50) String version) {
}
