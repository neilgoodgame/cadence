package com.cadence.api.email;

import com.cadence.api.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local-dev stand-in for {@link SesEmailService} - selected by {@code cadence.email.provider:
 * log} (docker-compose.yml's default), since a local box has no AWS credentials or verified
 * SES identity to actually deliver anything through. Logs the link at INFO instead of
 * sending it, so `docker compose logs backend` is the local "inbox" - grep for
 * "Verification email" after registering or hitting resend-verification.
 */
@Service
@ConditionalOnProperty(prefix = "cadence.email", name = "provider", havingValue = "log")
public class LoggingEmailService implements EmailService {

	private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

	@Override
	public void sendVerificationEmail(User user, String verificationLink) {
		log.info("Verification email for {} <{}>: {}", user.getName(), user.getEmail(), verificationLink);
	}
}
