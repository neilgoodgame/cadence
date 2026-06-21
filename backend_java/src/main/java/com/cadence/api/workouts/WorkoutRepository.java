package com.cadence.api.workouts;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkoutRepository extends JpaRepository<Workout, String> {

	List<Workout> findByCreatedByIdOrderByIdDesc(String createdById);

	/** The detail view walks the (lazy) step collection - fetch it eagerly here rather than in the list view. */
	@Query("select distinct w from Workout w left join fetch w.steps where w.id = :id")
	Optional<Workout> findByIdWithSteps(@Param("id") String id);
}
