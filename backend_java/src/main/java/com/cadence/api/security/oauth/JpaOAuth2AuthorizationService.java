package com.cadence.api.security.oauth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists {@link OAuth2Authorization} (the authorization code / access token / refresh
 * token issued for one grant) through a plain JPA entity instead of Spring Authorization
 * Server's own JDBC schema - see {@link OAuthAuthorization} for why. Attribute/metadata
 * maps are serialized the same way the framework's own {@link JdbcOAuth2AuthorizationService}
 * does: Jackson configured with Spring Security's OAuth2/core mixins, so that polymorphic
 * values captured mid-authorization-code-flow (the authenticated principal, the original
 * {@code OAuth2AuthorizationRequest}) round-trip correctly between the {@code /oauth/authorize}
 * and {@code /oauth/token} requests.
 */
@Service
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService {

	private final OAuthAuthorizationRepository repository;
	private final RegisteredClientRepository registeredClientRepository;
	private final JsonMapper jsonMapper;

	public JpaOAuth2AuthorizationService(OAuthAuthorizationRepository repository,
			RegisteredClientRepository registeredClientRepository) {
		this.repository = repository;
		this.registeredClientRepository = registeredClientRepository;
		this.jsonMapper = OAuthJacksonSupport.jsonMapper();
	}

	@Override
	@Transactional
	public void save(OAuth2Authorization authorization) {
		repository.save(toEntity(authorization));
	}

	@Override
	@Transactional
	public void remove(OAuth2Authorization authorization) {
		repository.deleteById(authorization.getId());
	}

	@Override
	public OAuth2Authorization findById(String id) {
		return repository.findById(id).map(this::toObject).orElse(null);
	}

	@Override
	public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
		if (tokenType == null) {
			return repository.findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValue(token, token, token, token)
					.map(this::toObject).orElse(null);
		}
		String value = tokenType.getValue();
		var entity = switch (value) {
			case OAuth2ParameterNames.STATE -> repository.findByState(token);
			case OAuth2ParameterNames.CODE -> repository.findByAuthorizationCodeValue(token);
			case OAuth2ParameterNames.ACCESS_TOKEN -> repository.findByAccessTokenValue(token);
			case OAuth2ParameterNames.REFRESH_TOKEN -> repository.findByRefreshTokenValue(token);
			default -> java.util.Optional.<OAuthAuthorization>empty();
		};
		return entity.map(this::toObject).orElse(null);
	}

	private OAuthAuthorization toEntity(OAuth2Authorization authorization) {
		OAuthAuthorization entity = repository.findById(authorization.getId()).orElseGet(OAuthAuthorization::new);
		entity.setId(authorization.getId());
		entity.setRegisteredClientId(authorization.getRegisteredClientId());
		entity.setPrincipalName(authorization.getPrincipalName());
		entity.setAuthorizationGrantType(authorization.getAuthorizationGrantType().getValue());
		entity.setAuthorizedScopes(String.join(",", authorization.getAuthorizedScopes()));
		entity.setAttributes(writeJson(authorization.getAttributes()));
		entity.setState(authorization.getAttribute(OAuth2ParameterNames.STATE));

		OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
		copyToken(code, entity::setAuthorizationCodeValue, entity::setAuthorizationCodeIssuedAt,
				entity::setAuthorizationCodeExpiresAt, entity::setAuthorizationCodeMetadata);

		OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getToken(OAuth2AccessToken.class);
		copyToken(accessToken, entity::setAccessTokenValue, entity::setAccessTokenIssuedAt,
				entity::setAccessTokenExpiresAt, entity::setAccessTokenMetadata);
		if (accessToken != null) {
			entity.setAccessTokenType(accessToken.getToken().getTokenType().getValue());
			entity.setAccessTokenScopes(String.join(",", accessToken.getToken().getScopes()));
		}

		OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getToken(OAuth2RefreshToken.class);
		copyToken(refreshToken, entity::setRefreshTokenValue, entity::setRefreshTokenIssuedAt,
				entity::setRefreshTokenExpiresAt, entity::setRefreshTokenMetadata);

		return entity;
	}

	private <T extends OAuth2Token> void copyToken(OAuth2Authorization.Token<T> token,
			Consumer<String> valueSetter, Consumer<java.time.Instant> issuedAtSetter,
			Consumer<java.time.Instant> expiresAtSetter, Consumer<String> metadataSetter) {
		if (token == null) {
			return;
		}
		valueSetter.accept(token.getToken().getTokenValue());
		issuedAtSetter.accept(token.getToken().getIssuedAt());
		expiresAtSetter.accept(token.getToken().getExpiresAt());
		metadataSetter.accept(writeJson(token.getMetadata()));
	}

	private OAuth2Authorization toObject(OAuthAuthorization entity) {
		RegisteredClient registeredClient = registeredClientRepository.findById(entity.getRegisteredClientId());
		if (registeredClient == null) {
			throw new IllegalStateException("Registered client not found: " + entity.getRegisteredClientId());
		}

		OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
				.id(entity.getId())
				.principalName(entity.getPrincipalName())
				.authorizationGrantType(resolveGrantType(entity.getAuthorizationGrantType()))
				.authorizedScopes(splitScopes(entity.getAuthorizedScopes()))
				.attributes(attrs -> attrs.putAll(readJsonMap(entity.getAttributes())));

		if (entity.getState() != null) {
			builder.attribute(OAuth2ParameterNames.STATE, entity.getState());
		}

		if (entity.getAuthorizationCodeValue() != null) {
			OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(entity.getAuthorizationCodeValue(),
					entity.getAuthorizationCodeIssuedAt(), entity.getAuthorizationCodeExpiresAt());
			builder.token(code, metadata -> metadata.putAll(readJsonMap(entity.getAuthorizationCodeMetadata())));
		}

		if (entity.getAccessTokenValue() != null) {
			OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
					entity.getAccessTokenValue(), entity.getAccessTokenIssuedAt(), entity.getAccessTokenExpiresAt(),
					splitScopes(entity.getAccessTokenScopes()));
			builder.token(accessToken, metadata -> metadata.putAll(readJsonMap(entity.getAccessTokenMetadata())));
		}

		if (entity.getRefreshTokenValue() != null) {
			OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(entity.getRefreshTokenValue(),
					entity.getRefreshTokenIssuedAt(), entity.getRefreshTokenExpiresAt());
			builder.token(refreshToken, metadata -> metadata.putAll(readJsonMap(entity.getRefreshTokenMetadata())));
		}

		return builder.build();
	}

	private AuthorizationGrantType resolveGrantType(String value) {
		if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(value)) {
			return AuthorizationGrantType.AUTHORIZATION_CODE;
		}
		if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(value)) {
			return AuthorizationGrantType.REFRESH_TOKEN;
		}
		if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(value)) {
			return AuthorizationGrantType.CLIENT_CREDENTIALS;
		}
		return new AuthorizationGrantType(value);
	}

	private Set<String> splitScopes(String value) {
		if (value == null || value.isBlank()) {
			return new HashSet<>();
		}
		return new HashSet<>(Arrays.asList(value.split(",")));
	}

	private String writeJson(Map<String, Object> map) {
		return jsonMapper.writeValueAsString(map);
	}

	private Map<String, Object> readJsonMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}
}
