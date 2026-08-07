package com.cadence.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminShoeVersionCreateRequest(@NotBlank @Size(max = 50) String version) {
}
