package com.cadence.api.gear.dto;

import com.cadence.api.gear.BikeKind;

public record BikeUpdateRequest(String name, BikeKind kind, String groupset, Integer distanceKm) {
}
