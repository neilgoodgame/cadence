package com.cadence.api.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

	private final JwtKeys keys;

	public JwksController(JwtKeys keys) {
		this.keys = keys;
	}

	@GetMapping("/.well-known/jwks.json")
	public Map<String, Object> jwks() {
		RSAKey jwk = new RSAKey.Builder(keys.publicKey())
				.keyID(keys.kid())
				.keyUse(KeyUse.SIGNATURE)
				.algorithm(JWSAlgorithm.RS256)
				.build();
		return Map.of("keys", List.of(jwk.toJSONObject()));
	}
}
