package com.cadence.api.gear;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeRepository extends JpaRepository<Bike, String> {

	List<Bike> findByAthleteIdOrderByIdDesc(String athleteId);
}
