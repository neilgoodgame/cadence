package com.cadence.api.activities;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, String>, JpaSpecificationExecutor<Activity> {

	// Multisport children are excluded: the parent already carries the sum of its legs' TSS,
	// so counting both would double the training load.
	@Query("select a.startDate, a.tss from Activity a where a.athlete.id = :athleteId and a.startDate >= :start and a.startDate < :end "
			+ "and a.parentActivity is null")
	List<Object[]> findStartDatesAndTssInRange(@Param("athleteId") String athleteId, @Param("start") Instant start, @Param("end") Instant end);

	@Query("select min(a.startDate) from Activity a where a.athlete.id = :athleteId")
	Optional<Instant> findEarliestStartDate(@Param("athleteId") String athleteId);

	@Query("select max(a.startDate) from Activity a where a.athlete.id = :athleteId")
	Optional<Instant> findLatestStartDate(@Param("athleteId") String athleteId);

	@Query("select a from Activity a where a.athlete.id = :athleteId and a.startDate >= :start and a.startDate < :end "
			+ "and a.parentActivity is null "
			+ "and not exists (select 1 from ScheduledWorkout sw where sw.activity = a)")
	List<Activity> findUnplannedInRange(@Param("athleteId") String athleteId, @Param("start") Instant start, @Param("end") Instant end);

	List<Activity> findByWorkoutId(String workoutId);

	@Query("select a.id from Activity a where a.parentActivity.id = :parentId order by a.startDate")
	List<String> findChildIds(@Param("parentId") String parentId);
}
