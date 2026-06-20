package com.cadence.api.users.dto;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, int expiresIn, String scope) {
}
