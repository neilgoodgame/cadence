package com.cadence.api.workouts;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutMatchScanRepository extends JpaRepository<WorkoutMatchScan, String> {

	Optional<WorkoutMatchScan> findFirstByWorkoutIdAndStatusIn(String workoutId, Iterable<WorkoutMatchScanStatus> statuses);

	Optional<WorkoutMatchScan> findByIdAndWorkoutId(String id, String workoutId);
}
