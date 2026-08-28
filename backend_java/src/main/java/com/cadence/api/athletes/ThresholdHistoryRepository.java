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
	// and TssRecomputeService read for an activity-scoped reference. Tiebroken by id (insertion
	// order) - effectiveFrom alone isn't unique (a same-day manual edit and activity-derived
	// entry tie on date, and without a secondary key the DB's tie order is undefined).
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
			String athleteId, ThresholdField field, LocalDate effectiveFrom);

	// The row that was actually the *recorded current value* as of a specific historical date -
	// filtered on currentFrom, not effectiveFrom (see ThresholdHistory.getCurrentFrom()'s
	// Javadoc: those two dates differ whenever a row only became current later than its own
	// qualifying activity's date, so filtering on effectiveFrom would let a not-yet-current row
	// match its own activity's date). This, not the method above, is what an activity-scoped zone
	// reference must use. Tiebroken by id - see the comment above.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndCurrentFromLessThanEqualOrderByCurrentFromDescIdDesc(
			String athleteId, ThresholdField field, LocalDate currentFrom);

	// The current entry - what the athlete's live profile is cached from, and what isStale
	// compares against. Tiebroken by id - see the comment above.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(String athleteId, ThresholdField field);

	// The full ledger for one field, most recent first - backs the history screen and the
	// dashboard summary's "current + previous" pair. Tiebroken by id - see the comment above.
	List<ThresholdHistory> findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(String athleteId, ThresholdField field);

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

	// Every entry that became current on a given date, regardless of source - paired with the
	// method above on the activity page: an entry here whose sourceActivity is a *different*
	// activity means this activity's own ingest/recompute pass is what revealed that earlier,
	// dormant effort as the new current value (see ThresholdHistory.getCurrentFrom()'s Javadoc).
	List<ThresholdHistory> findByAthleteIdAndCurrentFrom(String athleteId, LocalDate currentFrom);

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
