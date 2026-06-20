package com.cadence.api.security.jwt;

import java.util.List;

public record CreateJwtRequest(String athleteId, List<String> scopes, Integer expiresIn) {
}
