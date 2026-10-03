package com.cadence.api.athletes;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcceptedThresholdCandidateRepository extends JpaRepository<AcceptedThresholdCandidate, Long> {

	// Threshold suggestions' sanity-band bypass set - see ThresholdHistoryCalculator's
	// currentWindowValue/replayFullHistory/rejectedCandidates.
	List<AcceptedThresholdCandidate> findByAthleteIdAndField(String athleteId, ThresholdField field);

	Optional<AcceptedThresholdCandidate> findByAthleteIdAndFieldAndActivityId(
			String athleteId, ThresholdField field, String activityId);

	void deleteByAthleteIdAndFieldAndActivityId(String athleteId, ThresholdField field, String activityId);
}
