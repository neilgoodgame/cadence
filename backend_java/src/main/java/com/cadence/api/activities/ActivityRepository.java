package com.cadence.api.activities;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, String>, JpaSpecificationExecutor<Activity> {

	// Multisport children are excluded: the parent already carries the sum of its legs' TSS,
	// so counting both would double the training load. Duplicate recordings are excluded for
	// the same reason: only the designated primary counts.
	@Query("select a.startDate, a.tss from Activity a where a.athlete.id = :athleteId and a.startDate >= :start and a.startDate < :end "
			+ "and a.parentActivity is null and a.primaryActivity is null")
	List<Object[]> findStartDatesAndTssInRange(@Param("athleteId") String athleteId, @Param("start") Instant start, @Param("end") Instant end);

	@Query("select min(a.startDate) from Activity a where a.athlete.id = :athleteId")
	Optional<Instant> findEarliestStartDate(@Param("athleteId") String athleteId);

	@Query("select max(a.startDate) from Activity a where a.athlete.id = :athleteId")
	Optional<Instant> findLatestStartDate(@Param("athleteId") String athleteId);

	@Query("select a from Activity a where a.athlete.id = :athleteId and a.startDate >= :start and a.startDate < :end "
			+ "and a.parentActivity is null and a.primaryActivity is null "
			+ "and not exists (select 1 from ScheduledWorkout sw where sw.activity = a)")
	List<Activity> findUnplannedInRange(@Param("athleteId") String athleteId, @Param("start") Instant start, @Param("end") Instant end);

	List<Activity> findByWorkoutId(String workoutId);

	@Query("select a.id from Activity a where a.parentActivity.id = :parentId order by a.startDate")
	List<String> findChildIds(@Param("parentId") String parentId);

	@Query("select a.id from Activity a where a.primaryActivity.id = :primaryId order by a.startDate")
	List<String> findDuplicateIds(@Param("primaryId") String primaryId);

	boolean existsByPrimaryActivityId(String primaryActivityId);

	// One bulk statement rather than a derived deleteBy (which would load and delete row by
	// row); the DB-level cascades on record/lap/etc. clean up the dependents either way.
	@Modifying
	@Query("delete from Activity a where a.athlete.id = :athleteId")
	void deleteAllByAthleteId(@Param("athleteId") String athleteId);
}
