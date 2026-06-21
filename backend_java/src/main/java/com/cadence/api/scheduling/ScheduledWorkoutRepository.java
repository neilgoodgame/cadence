package com.cadence.api.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledWorkoutRepository extends JpaRepository<ScheduledWorkout, String> {

	List<ScheduledWorkout> findByAthleteIdAndDateBetweenOrderByDate(String athleteId, LocalDate from, LocalDate to);

	@Query("select s from ScheduledWorkout s where s.athlete.id = :athleteId and s.date = :date "
			+ "and s.status = com.cadence.api.scheduling.ScheduledWorkoutStatus.PLANNED and s.activity is null "
			+ "and s.workout.sport = :sport order by s.id")
	List<ScheduledWorkout> findMatchCandidates(
			@Param("athleteId") String athleteId, @Param("date") LocalDate date, @Param("sport") com.cadence.api.common.domain.Sport sport);

	long countByAthleteIdAndDateBetween(String athleteId, LocalDate from, LocalDate to);

	long countByAthleteIdAndStatusAndDateBetween(String athleteId, ScheduledWorkoutStatus status, LocalDate from, LocalDate to);

	long countByAthleteIdAndStatusAndDateGreaterThanEqualAndDateLessThan(
			String athleteId, ScheduledWorkoutStatus status, LocalDate from, LocalDate to);

	Optional<ScheduledWorkout> findByIdAndAthleteId(String id, String athleteId);

	// Callers read workout.name off the result, so the association needs to come back already
	// initialized - see GearService/ShoeService for why that's not safe to defer otherwise.
	@Query("select s from ScheduledWorkout s join fetch s.workout where s.athlete.id = :athleteId "
			+ "and s.status = com.cadence.api.scheduling.ScheduledWorkoutStatus.PLANNED and s.date >= :from "
			+ "order by s.date asc, s.id asc")
	List<ScheduledWorkout> findUpcomingPlannedWithWorkout(@Param("athleteId") String athleteId, @Param("from") LocalDate from);
}
