package com.cadence.api.security.jwt;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** The {@link JwtDecoder} that verifies RS256 JWTs minted by {@link JwtIssuer} on incoming requests. */
@Configuration
public class JwtDecoderConfig {

	@Bean
	public JwtDecoder jwtDecoder(JwtKeys keys) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();

		OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(keys.issuer());
		OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
				audience -> audience != null && audience.contains(keys.audience()));

		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
		return decoder;
	}
}
