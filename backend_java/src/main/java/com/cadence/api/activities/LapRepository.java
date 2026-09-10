package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LapRepository extends JpaRepository<Lap, Long> {

	List<Lap> findByActivityIdOrderByIndex(String activityId);

	// Eagerly fetches workoutStep in the same query - workoutStep is LAZY and this app runs
	// with spring.jpa.open-in-view: false, so a plain lazy access after this method returns
	// (e.g. while mapping to LapResponse) throws LazyInitializationException otherwise. Only
	// the read/response path needs this - internal lookups (e.g. deleting existing laps before
	// a replace) don't touch workoutStep at all, so they stay on the plain query above.
	@Query("SELECT l FROM Lap l LEFT JOIN FETCH l.workoutStep WHERE l.activity.id = :activityId ORDER BY l.index")
	List<Lap> findByActivityIdOrderByIndexFetchWorkoutStep(String activityId);
}
