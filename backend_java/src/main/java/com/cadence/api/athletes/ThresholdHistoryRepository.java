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

	// As above, but excluding correction-only rows (issue #493 - a backdated manual edit that
	// never actually took over, because a later row already existed or it was already outside
	// the window the moment it was saved). This is THE "what's current" lookup - every one of
	// recordManualValue's own dedup check, isStale, summaryFor, and
	// ThresholdHistoryCalculator.manualAnchorCandidate (#484's fix) must use this, not the
	// unfiltered method above, so a correction-only row is never mistaken for the live value.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(
			String athleteId, ThresholdField field);

	// The full ledger for one field, most recent first - backs the history screen. Tiebroken by
	// id - see the comment above. Includes correction-only rows (issue #493) - the full ledger
	// view needs to show them.
	List<ThresholdHistory> findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(String athleteId, ThresholdField field);

	// As above, but excluding correction-only rows - summaryFor's "current + previous" pair
	// must not treat one as either.
	List<ThresholdHistory> findByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(
			String athleteId, ThresholdField field);

	// recordManualValue's "does a later row already exist" check (issue #493's "became current"
	// rule, condition 1) - ANY row, correction-only or not; a later correction-only row still
	// means this one isn't the chronologically latest thing recorded.
	boolean existsByAthleteIdAndFieldAndEffectiveFromGreaterThan(String athleteId, ThresholdField field, LocalDate effectiveFrom);

	// ThresholdHistoryCalculator.replayFullHistory's manual-row merge (issue #504) - every
	// still-current (not correction-only) manual row, oldest first, so a rebuild can re-inject
	// each one as a window candidate at its own effectiveFrom instead of silently losing it to
	// the measured-only replay.
	List<ThresholdHistory> findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseOrderByEffectiveFromAsc(
			String athleteId, ThresholdField field, ThresholdHistoryKind kind);

	// list_threshold_history MCP tool's optional date-range restriction - `fromDate`/`toDate` are
	// each independently optional, so a derived "Between" method (which requires both bounds)
	// doesn't fit; mirrors the "(:param is null or ...)" optional-filter shape used elsewhere in
	// this repository (see findDecouplingBackfillCandidates in ActivityRepository). Named
	// `fromDate`/`toDate`, not `from`/`to` - the latter are JPQL reserved words (the FROM clause),
	// so using them as bind parameter names fails to parse. The `cast(... as date)` on the two
	// `is null` checks works around a Postgres/JDBC limitation, not a JPQL one: with a null-valued
	// parameter whose only other occurrence is a typed comparison, the driver can still fail to
	// infer that same type for the `is null` occurrence ("could not determine data type of
	// parameter") - the explicit cast removes the ambiguity.
	@Query("select t from ThresholdHistory t where t.athlete.id = :athleteId and t.field = :field "
			+ "and (cast(:fromDate as date) is null or t.effectiveFrom >= :fromDate) "
			+ "and (cast(:toDate as date) is null or t.effectiveFrom <= :toDate) "
			+ "order by t.effectiveFrom desc, t.id desc")
	List<ThresholdHistory> findByAthleteIdAndFieldInEffectiveFromRange(@Param("athleteId") String athleteId,
			@Param("field") ThresholdField field, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

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

	// rebuildHistory's actual delete (issue #504) - a full rebuild replays activities only, so it
	// must only ever touch MEASURED rows: a manual or correction-only row isn't rederivable from
	// activities at all (that's the whole point of it), so deleting it here would silently lose a
	// real, intentional correction on every rebuild. Same bulk-@Modifying reasoning as
	// deleteByAthleteIdAndField above - entityManager.clear() inside the replay would otherwise
	// discard a derived deleteBy's unflushed pending removal.
	@Modifying
	@Query("delete from ThresholdHistory t where t.athlete.id = :athleteId and t.field = :field and t.kind = :kind")
	void deleteByAthleteIdAndFieldAndKind(
			@Param("athleteId") String athleteId, @Param("field") ThresholdField field, @Param("kind") ThresholdHistoryKind kind);

	// ThresholdHistoryCalculator.replayFrom's seed (issue #545) - every measured row dated
	// before the scan start, oldest first: each one re-enters the replay's window as a candidate
	// at its own effectiveFrom, exactly like replayFullHistory would have left it there. Rows
	// whose candidate was already removed from the window by a better arrival are harmless to
	// include - that better candidate is itself either one of these rows (and outlives this
	// one, since it's dated later) or a scan candidate that re-removes it.
	List<ThresholdHistory> findByAthleteIdAndFieldAndKindAndEffectiveFromLessThanOrderByEffectiveFromAsc(
			String athleteId, ThresholdField field, ThresholdHistoryKind kind, LocalDate effectiveFrom);

	// Threshold suggestions' undo-accept: removes the ledger row accept() created for this
	// candidate, so a fresh recompute isn't comparing the sanity band against itself. See
	// ThresholdSuggestionService.undoAccept.
	@Modifying
	@Query("delete from ThresholdHistory t where t.athlete.id = :athleteId and t.field = :field "
			+ "and t.sourceActivity.id = :activityId")
	void deleteByAthleteIdAndFieldAndSourceActivityId(
			@Param("athleteId") String athleteId, @Param("field") ThresholdField field, @Param("activityId") String activityId);

	// ThresholdDecayRecomputeService's seed: the ledger's current value as of `before` minus one
	// day - any kind, including decayed. Tiebroken by id - see the comment on the other
	// currentFrom lookup above.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndCurrentFromLessThanOrderByCurrentFromDescIdDesc(
			String athleteId, ThresholdField field, LocalDate before);

	// ThresholdDecayRecomputeService's seed: the latest non-decayed row before `before` - the
	// anchor simulate() decays away from.
	Optional<ThresholdHistory> findFirstByAthleteIdAndFieldAndEffectiveFromLessThanAndKindNotOrderByEffectiveFromDescIdDesc(
			String athleteId, ThresholdField field, LocalDate before, ThresholdHistoryKind kind);

	// ThresholdDecayRecomputeService's manual-entry gathering - every surviving (not correction-
	// only, same exclusion ThresholdHistoryCalculator.replayFullHistory's own manual-row merge
	// already applies) manual row in [from, to], so simulate() can respect its anchor-reset
	// effect without duplicating it (ManualEntry.alreadyRecorded).
	List<ThresholdHistory> findByAthleteIdAndFieldAndKindAndCorrectionOnlyFalseAndEffectiveFromBetween(
			String athleteId, ThresholdField field, ThresholdHistoryKind kind, LocalDate from, LocalDate to);

	// ThresholdDecayRecomputeService's delete-then-regenerate (design doc section 4.2) - a single
	// pattern that implements every row of its own table uniformly, including "append only"
	// (nothing exists at/after `from` yet, so this simply matches nothing). Same bulk-@Modifying
	// reasoning as deleteByAthleteIdAndFieldAndKind above.
	@Modifying
	@Query("delete from ThresholdHistory t where t.athlete.id = :athleteId and t.field = :field "
			+ "and t.currentFrom >= :from and t.kind in :kinds")
	void deleteByAthleteIdAndFieldAndCurrentFromGreaterThanEqualAndKindIn(
			@Param("athleteId") String athleteId, @Param("field") ThresholdField field, @Param("from") LocalDate from,
			@Param("kinds") Collection<ThresholdHistoryKind> kinds);
}
