package com.cadence.api.security.pat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, String> {

	Optional<PersonalAccessToken> findByPrefix(String prefix);

	List<PersonalAccessToken> findByUserIdOrderByCreatedDesc(String userId);
}
