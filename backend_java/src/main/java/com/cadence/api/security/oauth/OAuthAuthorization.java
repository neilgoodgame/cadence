package com.cadence.api.security.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Durable storage for an issued {@code OAuth2Authorization} - the authorization code,
 * access token, and refresh token (plus bookkeeping attributes) tied to one grant.
 * Persisted through {@link JpaOAuth2AuthorizationService} rather than Spring
 * Authorization Server's own JDBC schema; the id is whatever Spring Authorization
 * Server itself assigns (an internal UUID, never exposed through the REST contract),
 * not one of this codebase's {@code prefix_...} resource ids.
 */
@Entity
@Table(name = "oauth_authorization")
public class OAuthAuthorization {

	@Id
	@Column(length = 40, nullable = false)
	private String id;

	@Column(name = "registered_client_id", nullable = false)
	private String registeredClientId;

	@Column(name = "principal_name", nullable = false)
	private String principalName;

	@Column(name = "authorization_grant_type", nullable = false)
	private String authorizationGrantType;

	@Column(name = "authorized_scopes", nullable = false)
	private String authorizedScopes = "";

	@Column(name = "attributes")
	private String attributes;

	@Column(name = "state")
	private String state;

	@Column(name = "authorization_code_value")
	private String authorizationCodeValue;

	@Column(name = "authorization_code_issued_at")
	private Instant authorizationCodeIssuedAt;

	@Column(name = "authorization_code_expires_at")
	private Instant authorizationCodeExpiresAt;

	@Column(name = "authorization_code_metadata")
	private String authorizationCodeMetadata;

	@Column(name = "access_token_value")
	private String accessTokenValue;

	@Column(name = "access_token_issued_at")
	private Instant accessTokenIssuedAt;

	@Column(name = "access_token_expires_at")
	private Instant accessTokenExpiresAt;

	@Column(name = "access_token_metadata")
	private String accessTokenMetadata;

	@Column(name = "access_token_type")
	private String accessTokenType;

	@Column(name = "access_token_scopes")
	private String accessTokenScopes;

	@Column(name = "refresh_token_value")
	private String refreshTokenValue;

	@Column(name = "refresh_token_issued_at")
	private Instant refreshTokenIssuedAt;

	@Column(name = "refresh_token_expires_at")
	private Instant refreshTokenExpiresAt;

	@Column(name = "refresh_token_metadata")
	private String refreshTokenMetadata;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getRegisteredClientId() {
		return registeredClientId;
	}

	public void setRegisteredClientId(String registeredClientId) {
		this.registeredClientId = registeredClientId;
	}

	public String getPrincipalName() {
		return principalName;
	}

	public void setPrincipalName(String principalName) {
		this.principalName = principalName;
	}

	public String getAuthorizationGrantType() {
		return authorizationGrantType;
	}

	public void setAuthorizationGrantType(String authorizationGrantType) {
		this.authorizationGrantType = authorizationGrantType;
	}

	public String getAuthorizedScopes() {
		return authorizedScopes;
	}

	public void setAuthorizedScopes(String authorizedScopes) {
		this.authorizedScopes = authorizedScopes;
	}

	public String getAttributes() {
		return attributes;
	}

	public void setAttributes(String attributes) {
		this.attributes = attributes;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getAuthorizationCodeValue() {
		return authorizationCodeValue;
	}

	public void setAuthorizationCodeValue(String authorizationCodeValue) {
		this.authorizationCodeValue = authorizationCodeValue;
	}

	public Instant getAuthorizationCodeIssuedAt() {
		return authorizationCodeIssuedAt;
	}

	public void setAuthorizationCodeIssuedAt(Instant authorizationCodeIssuedAt) {
		this.authorizationCodeIssuedAt = authorizationCodeIssuedAt;
	}

	public Instant getAuthorizationCodeExpiresAt() {
		return authorizationCodeExpiresAt;
	}

	public void setAuthorizationCodeExpiresAt(Instant authorizationCodeExpiresAt) {
		this.authorizationCodeExpiresAt = authorizationCodeExpiresAt;
	}

	public String getAuthorizationCodeMetadata() {
		return authorizationCodeMetadata;
	}

	public void setAuthorizationCodeMetadata(String authorizationCodeMetadata) {
		this.authorizationCodeMetadata = authorizationCodeMetadata;
	}

	public String getAccessTokenValue() {
		return accessTokenValue;
	}

	public void setAccessTokenValue(String accessTokenValue) {
		this.accessTokenValue = accessTokenValue;
	}

	public Instant getAccessTokenIssuedAt() {
		return accessTokenIssuedAt;
	}

	public void setAccessTokenIssuedAt(Instant accessTokenIssuedAt) {
		this.accessTokenIssuedAt = accessTokenIssuedAt;
	}

	public Instant getAccessTokenExpiresAt() {
		return accessTokenExpiresAt;
	}

	public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) {
		this.accessTokenExpiresAt = accessTokenExpiresAt;
	}

	public String getAccessTokenMetadata() {
		return accessTokenMetadata;
	}

	public void setAccessTokenMetadata(String accessTokenMetadata) {
		this.accessTokenMetadata = accessTokenMetadata;
	}

	public String getAccessTokenType() {
		return accessTokenType;
	}

	public void setAccessTokenType(String accessTokenType) {
		this.accessTokenType = accessTokenType;
	}

	public String getAccessTokenScopes() {
		return accessTokenScopes;
	}

	public void setAccessTokenScopes(String accessTokenScopes) {
		this.accessTokenScopes = accessTokenScopes;
	}

	public String getRefreshTokenValue() {
		return refreshTokenValue;
	}

	public void setRefreshTokenValue(String refreshTokenValue) {
		this.refreshTokenValue = refreshTokenValue;
	}

	public Instant getRefreshTokenIssuedAt() {
		return refreshTokenIssuedAt;
	}

	public void setRefreshTokenIssuedAt(Instant refreshTokenIssuedAt) {
		this.refreshTokenIssuedAt = refreshTokenIssuedAt;
	}

	public Instant getRefreshTokenExpiresAt() {
		return refreshTokenExpiresAt;
	}

	public void setRefreshTokenExpiresAt(Instant refreshTokenExpiresAt) {
		this.refreshTokenExpiresAt = refreshTokenExpiresAt;
	}

	public String getRefreshTokenMetadata() {
		return refreshTokenMetadata;
	}

	public void setRefreshTokenMetadata(String refreshTokenMetadata) {
		this.refreshTokenMetadata = refreshTokenMetadata;
	}
}
