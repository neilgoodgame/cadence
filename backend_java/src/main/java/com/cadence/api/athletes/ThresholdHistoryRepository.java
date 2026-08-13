package com.cadence.api.athletes;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThresholdHistoryRepository extends JpaRepository<ThresholdHistory, Long> {

	// The ledger entry effective as of a specific historical date - what ZoneService.referenceFor
	// and TssRecomputeService read for an activity-scoped reference.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
			String athleteId, ThresholdField field, LocalDate effectiveFrom);

	// The current entry - what the athlete's live profile is cached from, and what isStale
	// compares against.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldOrderByEffectiveFromDesc(String athleteId, ThresholdField field);

	// The full ledger for one field, most recent first - backs the history screen and the
	// dashboard summary's "current + previous" pair.
	List<ThresholdHistory> findByAthleteIdAndFieldOrderByEffectiveFromDesc(String athleteId, ThresholdField field);

	// Export - oldest first (chronological), optionally narrowed to the fields a sport filter maps to.
	List<ThresholdHistory> findByAthleteIdOrderByEffectiveFrom(String athleteId);

	List<ThresholdHistory> findByAthleteIdAndFieldInOrderByEffectiveFrom(String athleteId, Collection<ThresholdField> fields);

	// Export's "counts" metadata block - same filtered scope as the two finders above, just a
	// count instead of materializing every row.
	long countByAthleteId(String athleteId);

	long countByAthleteIdAndFieldIn(String athleteId, Collection<ThresholdField> fields);

	// Every entry this activity is (or was) the source of - the activity page's "currently/
	// previously defines your FTP" indicator.
	List<ThresholdHistory> findBySourceActivityId(String activityId);

	void deleteByAthleteIdAndField(String athleteId, ThresholdField field);
}
