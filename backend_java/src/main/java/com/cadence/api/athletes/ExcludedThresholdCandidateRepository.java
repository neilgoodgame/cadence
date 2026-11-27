package com.cadence.api.athletes;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExcludedThresholdCandidateRepository extends JpaRepository<ExcludedThresholdCandidate, Long> {

	// Issue #616: the activity ids every threshold scan leaves out for (athlete, field) - see
	// ThresholdHistoryCalculator.excludedActivityIds.
	@Query("select e.activity.id from ExcludedThresholdCandidate e where e.athlete.id = :athleteId and e.field = :field")
	List<String> findActivityIdsByAthleteIdAndField(@Param("athleteId") String athleteId, @Param("field") ThresholdField field);

	List<ExcludedThresholdCandidate> findByAthleteIdAndField(String athleteId, ThresholdField field);

	Optional<ExcludedThresholdCandidate> findByAthleteIdAndFieldAndActivityId(
			String athleteId, ThresholdField field, String activityId);

	void deleteByAthleteIdAndFieldAndActivityId(String athleteId, ThresholdField field, String activityId);
}
