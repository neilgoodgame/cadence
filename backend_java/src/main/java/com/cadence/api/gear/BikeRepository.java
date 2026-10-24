package com.cadence.api.gear;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeRepository extends JpaRepository<Bike, String> {

	List<Bike> findByAthleteIdOrderByIdDesc(String athleteId);

	long countByAthleteId(String athleteId);

	Optional<Bike> findByIdAndAthleteId(String id, String athleteId);
}
