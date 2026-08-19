package com.cadence.api.email;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends via Amazon SES. Credentials come from the default AWS provider chain (the EC2
 * instance profile in prod - see infra/EC2_BACKEND_SKETCH.md's IAM section, which needs
 * {@code ses:SendEmail} added scoped to the verified identity ARN); nothing static is
 * configured here. {@code fromAddress} must be a verified SES identity/domain.
 *
 * The active {@code EmailService} bean - this or {@link LoggingEmailService} - is chosen by
 * {@code cadence.email.provider} (default {@code ses}; local dev's docker-compose.yml sets
 * {@code log} instead, since a local box has neither AWS credentials nor a verified SES
 * identity to actually deliver anything).
 */
@Service
@ConditionalOnProperty(prefix = "cadence.email", name = "provider", havingValue = "ses", matchIfMissing = true)
public class SesEmailService implements EmailService {

	private static final Logger log = LoggerFactory.getLogger(SesEmailService.class);

	private final SesV2Client sesClient;
	private final String fromAddress;

	public SesEmailService(CadenceProperties properties) {
		this.sesClient = SesV2Client.builder().region(Region.of(properties.email().sesRegion())).build();
		this.fromAddress = properties.email().fromAddress();
	}

	/**
	 * Async: the caller (EmailVerificationService) has already committed the token row by
	 * the time this runs, so a slow or failed SES call never delays or fails the HTTP
	 * response it was triggered from. Failures are logged, not rethrown - there's no
	 * request left to fail, and the athlete can always hit resend-verification.
	 */
	@Override
	@Async
	public void sendVerificationEmail(User user, String verificationLink) {
		String textBody = "Hi " + user.getName() + ",\n\n"
				+ "Confirm your email address to finish setting up your Cadence account:\n\n"
				+ verificationLink
				+ "\n\nThis link expires in 24 hours. If you didn't create a Cadence account, "
				+ "you can safely ignore this email.";

		SendEmailRequest request = SendEmailRequest.builder()
				.fromEmailAddress(fromAddress)
				.destination(Destination.builder().toAddresses(user.getEmail()).build())
				.content(EmailContent.builder()
						.simple(Message.builder()
								.subject(Content.builder().data("Verify your Cadence email address").build())
								.body(Body.builder().text(Content.builder().data(textBody).build()).build())
								.build())
						.build())
				.build();

		try {
			sesClient.sendEmail(request);
		}
		catch (Exception e) {
			log.error("Failed to send verification email to user {}", user.getId(), e);
		}
	}
}
