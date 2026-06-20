package com.cadence.api.tokens.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The secret is returned only here - at creation or rotation - and never again. */
public record AccessTokenCreatedResponse(@JsonUnwrapped AccessTokenResponse token, String secret) {
}
