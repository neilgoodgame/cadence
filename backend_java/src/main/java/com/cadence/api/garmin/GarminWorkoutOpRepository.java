package com.cadence.api.garmin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarminWorkoutOpRepository extends JpaRepository<GarminWorkoutOp, String> {

	List<GarminWorkoutOp> findByOwnerIdAndStatusOrderByCreatedAsc(String ownerId, GarminWorkoutOpStatus status);

	List<GarminWorkoutOp> findByOwnerIdOrderByCreatedDesc(String ownerId);

	Optional<GarminWorkoutOp> findByIdAndOwnerId(String id, String ownerId);

	Optional<GarminWorkoutOp> findFirstByOwnerIdAndStatusAndKindAndWorkoutId(String ownerId,
			GarminWorkoutOpStatus status, GarminWorkoutOpKind kind, String workoutId);

	List<GarminWorkoutOp> findByOwnerIdAndStatusAndScheduledWorkoutId(String ownerId, GarminWorkoutOpStatus status,
			String scheduledWorkoutId);

	long countByOwnerIdAndStatus(String ownerId, GarminWorkoutOpStatus status);
}
