package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, String> {

	List<Tag> findByAthleteIdOrderByName(String athleteId);

	Optional<Tag> findByAthleteIdAndNameIgnoreCase(String athleteId, String name);

	// Plural form for callers that must tolerate more than one case-insensitive match (e.g. the
	// athlete already has both "race" and "Race" via the older exact-match attach-by-name path) -
	// the singular findByAthleteIdAndNameIgnoreCase throws NonUniqueResultException in that case.
	List<Tag> findAllByAthleteIdAndNameIgnoreCase(String athleteId, String name);

	Optional<Tag> findByAthleteIdAndName(String athleteId, String name);

	Optional<Tag> findByIdAndAthleteId(String id, String athleteId);
}
