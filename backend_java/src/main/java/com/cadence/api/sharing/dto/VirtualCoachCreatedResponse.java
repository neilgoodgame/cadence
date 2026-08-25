package com.cadence.api.sharing.dto;

import com.cadence.api.tokens.dto.AccessTokenCreatedResponse;

/** {@code password} and {@code token.secret} are both shown once and never retrievable again -
 * {@code email}+{@code password} log the coach into Cadence itself (e.g. completing an MCP
 * client's OAuth authorization as it), {@code token} is a delegated personal access token for
 * clients that accept a bearer token directly instead. */
public record VirtualCoachCreatedResponse(ShareResponse share, String email, String password, AccessTokenCreatedResponse token) {
}
