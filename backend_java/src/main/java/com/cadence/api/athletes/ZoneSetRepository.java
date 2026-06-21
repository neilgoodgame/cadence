package com.cadence.api.athletes;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneSetRepository extends JpaRepository<ZoneSet, Long> {

	Optional<ZoneSet> findByAthleteIdAndType(String athleteId, ZoneType type);

	List<ZoneSet> findByAthleteId(String athleteId);

	boolean existsByAthleteIdAndType(String athleteId, ZoneType type);
}
