package com.cadence.api.gear.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ComponentCreateRequest(@NotBlank String name, @NotNull Integer limitKm, Integer km, String model) {
}
