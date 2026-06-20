package com.cadence.api.gear.dto;

import com.cadence.api.gear.BikeKind;
import jakarta.validation.constraints.NotBlank;

public record BikeCreateRequest(@NotBlank String name, BikeKind kind, String groupset, Integer distanceKm) {
}
