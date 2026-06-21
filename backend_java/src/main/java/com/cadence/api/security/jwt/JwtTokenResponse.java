package com.cadence.api.security.jwt;

import java.util.Map;

public record JwtTokenResponse(String token, String tokenType, int expiresIn, Map<String, Object> claims) {
}
