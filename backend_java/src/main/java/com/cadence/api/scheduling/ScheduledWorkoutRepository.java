package com.cadence.api.scheduling;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledWorkoutRepository extends JpaRepository<ScheduledWorkout, String> {

	List<ScheduledWorkout> findByAthleteIdAndDateBetweenOrderByDate(String athleteId, LocalDate from, LocalDate to);

	List<ScheduledWorkout> findByAthleteIdOrderByDate(String athleteId);

	// Export's "counts" metadata block.
	long countByAthleteId(String athleteId);

	long countByAthleteIdAndWorkoutSport(String athleteId, com.cadence.api.common.domain.Sport sport);

	// PESSIMISTIC_WRITE - two concurrent ingests for the same athlete/date/sport could otherwise
	// both read the same candidate row(s) before either claims one, a pre-existing race that
	// WorkoutAutoMatchService#attemptMatch's tie-break widens slightly (more work now happens
	// between this read and the eventual save). Locked rows release when attemptMatch's
	// @Transactional method returns.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
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

	// LEFT (not inner) join - assignedBy is null for the common self-scheduled case, which an
	// inner join would silently exclude entirely. Lets SchedulingMapper safely read
	// assignedBy.getName()/.isVirtual() - see its Javadoc.
	@Query("select s from ScheduledWorkout s left join fetch s.assignedBy where s.id = :id")
	Optional<ScheduledWorkout> findByIdWithAssignedBy(@Param("id") String id);

	// Callers read workout.name off the result, so the association needs to come back already
	// initialized - see GearService/ShoeService for why that's not safe to defer otherwise.
	@Query("select s from ScheduledWorkout s join fetch s.workout where s.athlete.id = :athleteId "
			+ "and s.status = com.cadence.api.scheduling.ScheduledWorkoutStatus.PLANNED and s.date >= :from "
			+ "order by s.date asc, s.id asc")
	List<ScheduledWorkout> findUpcomingPlannedWithWorkout(@Param("athleteId") String athleteId, @Param("from") LocalDate from);
}
