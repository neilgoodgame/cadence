package com.cadence.api.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cadence")
public record CadenceProperties(
		Jwt jwt, Cors cors, Uploads uploads, DataImport dataImport, Oauth oauth, RateLimit rateLimit, Email email,
		Garmin garmin) {

	public record Jwt(String privateKeyPath, String publicKeyPath, String kid, String issuer, String audience) {
	}

	public record Cors(List<String> allowedOrigins) {
	}

	public record Uploads(String mediaRoot, long maxUploadBytes, int maxBatchFiles) {
	}

	public record DataImport(long maxFileBytes) {
	}

	/**
	 * {@code issuer} is set explicitly (rather than left to Spring Authorization Server's
	 * request-derived resolution) so the value emitted in OAuth/OIDC discovery metadata stays
	 * correct behind CloudFront, which terminates TLS and proxies plain HTTP to the origin -
	 * same reasoning as {@link Jwt#issuer()}.
	 */
	public record Oauth(String firstPartyClientSecret, String mcpClientSecret, String issuer) {
	}

	public record RateLimit(int registerMaxAttempts, int registerWindowMinutes) {
	}

	public record Email(String fromAddress, String verificationBaseUrl, int verificationTtlHours,
			int resendCooldownSeconds, String sesRegion, String provider) {
	}

	/**
	 * {@code encryptionKey} is base64 for 32 bytes (AES-256); blank leaves Garmin key storage
	 * disabled. {@code apiBaseUrl} is the Personal API host (configurable only so tests can point
	 * at a stub). How many activities a sync pulls is a per-athlete setting, not configuration.
	 */
	public record Garmin(String encryptionKey, String apiBaseUrl) {
	}
}
