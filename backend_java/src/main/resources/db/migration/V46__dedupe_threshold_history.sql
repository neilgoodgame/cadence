-- Removes exact-duplicate threshold_history rows left behind by a since-fixed bug in
-- ThresholdHistoryRepository.deleteByAthleteIdAndField (see that method's Javadoc): a plain
-- derived deleteBy... only marked rows for removal in the persistence context rather than
-- issuing the DELETE, so a bulk history rebuild's entityManager.clear() discarded the pending
-- delete and every rebuild appended a fresh duplicate copy of the ledger on top of the old one.
-- Keeps the lowest id (earliest-inserted) row per duplicate group.
DELETE FROM threshold_history newer
USING threshold_history older
WHERE newer.id > older.id
  AND newer.athlete_id = older.athlete_id
  AND newer.field = older.field
  AND newer.value_numeric IS NOT DISTINCT FROM older.value_numeric
  AND newer.value_pace = older.value_pace
  AND newer.source_activity_id IS NOT DISTINCT FROM older.source_activity_id
  AND newer.effective_from = older.effective_from;
