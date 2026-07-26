package com.cadence.api.workouts;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutFolderRepository extends JpaRepository<WorkoutFolder, String> {

	List<WorkoutFolder> findByCreatedByIdOrderByNameAsc(String createdById);

	boolean existsByCreatedByIdAndName(String createdById, String name);
}
