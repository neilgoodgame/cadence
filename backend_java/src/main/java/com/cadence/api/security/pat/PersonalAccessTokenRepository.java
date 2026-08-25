package com.cadence.api.security.pat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, String> {

	Optional<PersonalAccessToken> findByPrefix(String prefix);

	List<PersonalAccessToken> findByUserIdOrderByCreatedDesc(String userId);

	// Cleans up a coach's delegated token(s) when the relationship backing them is revoked -
	// see SharingService.deleteShare. A derived delete, not a bulk @Query one, so each row's
	// @PrePersist/lifecycle callbacks (none exist today, but keeps this consistent with the
	// rest of the repository layer) still run.
	long deleteByUserIdAndDelegatedAthleteId(String userId, String delegatedAthleteId);
}
