package com.cadence.api.races;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RaceRepository extends JpaRepository<Race, String> {
	List<Race> findByAthleteIdOrderByDateAsc(String athleteId);
}
