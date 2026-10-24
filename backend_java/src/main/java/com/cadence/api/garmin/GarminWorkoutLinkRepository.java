package com.cadence.api.garmin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarminWorkoutLinkRepository extends JpaRepository<GarminWorkoutLink, Long> {

	Optional<GarminWorkoutLink> findByOwnerIdAndWorkoutId(String ownerId, String workoutId);

	List<GarminWorkoutLink> findByOwnerId(String ownerId);
}
