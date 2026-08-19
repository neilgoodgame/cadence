package com.cadence.api.email;

import com.cadence.api.users.User;

/** Transactional email out of the API. One implementation today ({@link SesEmailService}) - an
 * interface here purely so EmailVerificationService can be tested without a real SES call. */
public interface EmailService {

	void sendVerificationEmail(User user, String verificationLink);
}
