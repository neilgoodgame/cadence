package com.cadence.api.garmin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarminScheduleLinkRepository extends JpaRepository<GarminScheduleLink, Long> {

	Optional<GarminScheduleLink> findByOwnerIdAndScheduledWorkoutId(String ownerId, String scheduledWorkoutId);

	List<GarminScheduleLink> findByOwnerId(String ownerId);
}
