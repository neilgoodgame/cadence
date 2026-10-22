package com.cadence.api.garmin;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarminConnectionRepository extends JpaRepository<GarminConnection, String> {

	Optional<GarminConnection> findByOwnerId(String ownerId);

	void deleteByOwnerId(String ownerId);
}
