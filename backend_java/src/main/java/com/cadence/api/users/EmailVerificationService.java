package com.cadence.api.users;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.email.EmailService;
import com.cadence.api.security.oauth.CadenceTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the email-verification token sent to password-signup athletes (social
 * signups skip this entirely - see UserService.register). Tokens are stored hashed
 * (SHA-256, looked up directly by hash - no visible prefix needed since, unlike a personal
 * access token, this is never typed by a human or reused across requests).
 */
@Service
public class EmailVerificationService {

	private static final String TOKEN_PREFIX = "cad_evt_";

	private final EmailVerificationTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final EmailService emailService;
	private final CadenceProperties.Email properties;

	public EmailVerificationService(EmailVerificationTokenRepository tokenRepository, UserRepository userRepository,
			EmailService emailService, CadenceProperties properties) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.emailService = emailService;
		this.properties = properties.email();
	}

	/**
	 * Generates and persists a fresh token, then hands the raw value off to be mailed.
	 * Doesn't check whether the athlete is already verified or under a resend cooldown -
	 * {@link #resend} runs those first; registration doesn't need to since the user was
	 * just created unverified.
	 */
	@Transactional
	public void issueAndSend(User user) {
		String rawSecret = CadenceTokenGenerator.randomToken(TOKEN_PREFIX);

		EmailVerificationToken token = new EmailVerificationToken();
		token.setUser(user);
		token.setHashedSecret(hash(rawSecret));
		token.setExpiresAt(Instant.now().plus(Duration.ofHours(properties.verificationTtlHours())));
		tokenRepository.save(token);

		String link = properties.verificationBaseUrl() + "?token=" + rawSecret;
		emailService.sendVerificationEmail(user, link);
	}

	@Transactional
	public void verify(String rawSecret) {
		EmailVerificationToken token = tokenRepository.findByHashedSecret(hash(rawSecret))
				.filter(t -> t.isUsable(Instant.now()))
				.orElseThrow(() -> new ApiException(
						HttpStatus.BAD_REQUEST, "This verification link is invalid or has expired.", "token"));

		token.setUsedAt(Instant.now());
		tokenRepository.save(token);

		User user = token.getUser();
		user.setEmailVerified(true);
		userRepository.save(user);
	}

	@Transactional
	public void resend(String userId) {
		User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("No such user."));
		if (user.isEmailVerified()) {
			throw new ConflictException("This email address is already verified.");
		}
		tokenRepository.findTopByUserIdOrderByCreatedDesc(userId).ifPresent(last -> {
			Instant cooldownEnds = last.getCreated().plusSeconds(properties.resendCooldownSeconds());
			if (Instant.now().isBefore(cooldownEnds)) {
				throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
						"A verification email was just sent - wait a bit before requesting another.", null);
			}
		});
		issueAndSend(user);
	}

	private static String hash(String secret) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
