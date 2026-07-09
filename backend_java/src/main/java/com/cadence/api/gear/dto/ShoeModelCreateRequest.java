package com.cadence.api.gear.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShoeModelCreateRequest(
        @NotBlank @Size(max = 150) String manufacturer,
        @NotBlank @Size(max = 150) String model,
        @Size(max = 50) String version) {
}
