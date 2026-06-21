package com.cadence.api.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cadence")
public record CadenceProperties(Jwt jwt, Cors cors, Uploads uploads, Oauth oauth) {

	public record Jwt(String privateKeyPath, String publicKeyPath, String kid, String issuer, String audience) {
	}

	public record Cors(List<String> allowedOrigins) {
	}

	public record Uploads(String mediaRoot, long maxUploadBytes, int maxBatchFiles) {
	}

	public record Oauth(String firstPartyClientSecret) {
	}
}
