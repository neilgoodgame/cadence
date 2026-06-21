package com.cadence.api.gear.dto;

public record ShoeUpdateRequest(String name, Integer limitKm, Integer km, String image, Boolean retired) {
}
