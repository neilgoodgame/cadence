package com.cadence.api.users;

import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.dto.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailVerificationController {

	private final EmailVerificationService emailVerificationService;

	public EmailVerificationController(EmailVerificationService emailVerificationService) {
		this.emailVerificationService = emailVerificationService;
	}

	/** Public (see SecurityConfig) - reached by clicking the emailed link, which may not carry
	 * this browser's bearer token (a different device, or a session that's since expired). */
	@PostMapping("/v1/auth/verify-email")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		emailVerificationService.verify(request.token());
	}

	/** Authenticated - resends to the caller's own address rather than taking one as input, so
	 * this can't be used to probe whether an arbitrary email belongs to an account. */
	@PostMapping("/v1/auth/resend-verification")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resendVerification() {
		emailVerificationService.resend(AuthContextHolder.get().sub());
	}
}
