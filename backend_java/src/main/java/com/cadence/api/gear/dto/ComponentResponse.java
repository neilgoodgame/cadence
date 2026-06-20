package com.cadence.api.gear.dto;

public record ComponentResponse(String id, String bikeId, String name, int km, int limitKm, String model) {
}
