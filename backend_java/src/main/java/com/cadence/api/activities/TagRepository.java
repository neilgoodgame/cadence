package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, String> {

	List<Tag> findByAthleteIdOrderByName(String athleteId);

	Optional<Tag> findByAthleteIdAndNameIgnoreCase(String athleteId, String name);
}
