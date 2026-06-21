package com.cadence.api.gear.dto;

public record ComponentUpdateRequest(String name, Integer limitKm, Integer km, String model) {
}
