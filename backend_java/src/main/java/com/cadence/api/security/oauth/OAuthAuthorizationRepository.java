package com.cadence.api.security.oauth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAuthorizationRepository extends JpaRepository<OAuthAuthorization, String> {

	Optional<OAuthAuthorization> findByState(String state);

	Optional<OAuthAuthorization> findByAuthorizationCodeValue(String authorizationCodeValue);

	Optional<OAuthAuthorization> findByAccessTokenValue(String accessTokenValue);

	Optional<OAuthAuthorization> findByRefreshTokenValue(String refreshTokenValue);

	Optional<OAuthAuthorization> findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValue(
			String state, String authorizationCodeValue, String accessTokenValue, String refreshTokenValue);
}
