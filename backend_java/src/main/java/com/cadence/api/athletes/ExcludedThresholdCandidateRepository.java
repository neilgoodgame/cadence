package com.cadence.api.athletes;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExcludedThresholdCandidateRepository extends JpaRepository<ExcludedThresholdCandidate, Long> {

	// Issue #616: the activity ids every threshold scan leaves out for (athlete, field) - see
	// ThresholdHistoryCalculator.excludedActivityIds.
	@Query("select e.activity.id from ExcludedThresholdCandidate e where e.athlete.id = :athleteId and e.field = :field")
	List<String> findActivityIdsByAthleteIdAndField(@Param("athleteId") String athleteId, @Param("field") ThresholdField field);

	List<ExcludedThresholdCandidate> findByAthleteIdAndField(String athleteId, ThresholdField field);

	/** Every exclusion the athlete has, for the export (ExportWriter.writeThresholdExclusions). */
	List<ExcludedThresholdCandidate> findByAthleteIdOrderByFieldAscActivityIdAsc(String athleteId);

	/** The same, limited to one sport's activities, for a sport-scoped export. */
	List<ExcludedThresholdCandidate> findByAthleteIdAndActivitySportOrderByFieldAscActivityIdAsc(
			String athleteId, com.cadence.api.common.domain.Sport sport);

	Optional<ExcludedThresholdCandidate> findByAthleteIdAndFieldAndActivityId(
			String athleteId, ThresholdField field, String activityId);

	void deleteByAthleteIdAndFieldAndActivityId(String athleteId, ThresholdField field, String activityId);

	/** Excludes the activity unless it already is - two requests at once (a double-clicked
	 * "Exclude") can't collide on the unique constraint, which inside the caller's transaction
	 * would also abort it. Returns the number of rows inserted: 0 if it was already excluded. */
	@Modifying
	@Query(value = "INSERT INTO excluded_threshold_candidate (athlete_id, field, activity_id, set_by_id, created_at) "
			+ "VALUES (:athleteId, :field, :activityId, :setById, now()) "
			+ "ON CONFLICT ON CONSTRAINT unique_excluded_threshold_candidate DO NOTHING", nativeQuery = true)
	int insertIfAbsent(@Param("athleteId") String athleteId, @Param("field") String field,
			@Param("activityId") String activityId, @Param("setById") String setById);
}
