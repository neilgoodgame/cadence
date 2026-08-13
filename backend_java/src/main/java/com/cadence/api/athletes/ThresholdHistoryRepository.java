package com.cadence.api.athletes;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	// A plain `deleteBy...` derived method loads matching rows into the persistence context and
	// only marks them for removal there - the DELETE isn't actually issued until the next flush.
	// ThresholdHistoryCalculator.replayFullHistory (called right after this, inside the same
	// rebuildHistory transaction) calls entityManager.clear() after every activity to bound
	// memory on large replays (see its own Javadoc) - clear() discards pending unflushed removals
	// along with everything else, so the old rows silently survived and every rebuild appended a
	// fresh duplicate copy of the ledger instead of replacing it (found live: a real account's
	// ledger had exact triplicate rows after three rebuilds). A bulk @Modifying query executes
	// immediately against the database, same convention as RecordRepository.deleteByAthleteId.
	@Modifying
	@Query("delete from ThresholdHistory t where t.athlete.id = :athleteId and t.field = :field")
	void deleteByAthleteIdAndField(@Param("athleteId") String athleteId, @Param("field") ThresholdField field);
}
