package com.cadence.api.users;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

	Optional<EmailVerificationToken> findByHashedSecret(String hashedSecret);

	Optional<EmailVerificationToken> findTopByUserIdOrderByCreatedDesc(String userId);
}
