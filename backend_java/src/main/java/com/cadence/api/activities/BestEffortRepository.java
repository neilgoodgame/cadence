package com.cadence.api.activities;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BestEffortRepository extends JpaRepository<BestEffort, Long> {

	/** Used by the processing pipeline to upsert the per-activity best for a window. */
	Optional<BestEffort> findByAthleteIdAndKindAndWindowAndActivityId(
			String athleteId, BestEffortKind kind, String window, String activityId);

	/** Used by ExportWriter#writeBestEfforts for the unfiltered ("all sports") export - a full
	 * data export must include every row, duplicate-sourced or not, same reasoning as
	 * ActivityRepository#findByAthleteIdOrderByStartDate. */
	List<BestEffort> findByAthleteId(String athleteId);

	/** RaceBlockService's "best effort" key-session candidates - every effort for the athlete,
	 * re-capped via BestEffortController#capPerWindow against the race block's own date range.
	 * Unlike findByAthleteId above, a duplicate recording's row is excluded (issue #498) - it
	 * would otherwise let the same ride's effort count as a "key session" twice. */
	List<BestEffort> findByAthleteIdAndActivityPrimaryActivityIsNull(String athleteId);

	/** Used by ExportWriter#writeBestEfforts for a sport-filtered export - filtered on the
	 * linked activity's own sport (not the kind's cycling/running prefix) so a filtered
	 * export never references an activity_id that isn't also in that same file. */
	List<BestEffort> findByAthleteIdAndActivitySportOrderByKindAscWindowAscValueDesc(
			String athleteId, com.cadence.api.common.domain.Sport sport);

	long countByAthleteId(String athleteId);

	long countByAthleteIdAndActivitySport(String athleteId, com.cadence.api.common.domain.Sport sport);

	/** All records for a window, best-first (for higher-is-better kinds: power, HR). A
	 * duplicate recording's row is excluded (issue #498), same as the ranking queries below -
	 * otherwise it could rank ahead of and evict a genuinely different activity's row from the
	 * trimmed top-N, since both are copies of the exact same ride and so carry nearly identical
	 * values. Left un-deleted rather than filtered out of existence: same "keep the hidden copy,
	 * don't delete it" reversibility the activity row itself gets. */
	List<BestEffort> findByAthleteIdAndKindAndWindowAndActivityPrimaryActivityIsNullOrderByValueDesc(
			String athleteId, BestEffortKind kind, String window);

	/** All records for a window, best-first for pace (lower value = faster). Same duplicate
	 * exclusion as the Desc variant above. */
	List<BestEffort> findByAthleteIdAndKindAndWindowAndActivityPrimaryActivityIsNullOrderByValueAsc(
			String athleteId, BestEffortKind kind, String window);

	/** Issue #498: a duplicate recording's row is excluded from every best-effort ranking/listing
	 * query (this one and the two below) - it's the same ride as its primary, so it shouldn't
	 * compete for or occupy a ranked slot a genuinely different activity's effort could hold. */
	List<BestEffort> findByAthleteIdAndKindAndActivityPrimaryActivityIsNullAndDateGreaterThanEqualOrderByWindowAscValueDesc(
			String athleteId, BestEffortKind kind, LocalDate since);

	List<BestEffort> findByAthleteIdAndKindAndActivityPrimaryActivityIsNullOrderByWindowAscValueDesc(
			String athleteId, BestEffortKind kind);

	/** Issue #498's MCP list_best_efforts tool only: {@code includeDuplicates} can override the
	 * default exclusion above for ad-hoc inspection of a same-ride-twice pair. The REST
	 * BestEffortController and every other caller keep using the always-excluding finders
	 * above instead. */
	@Query("select be from BestEffort be where be.athlete.id = :athleteId and be.kind = :kind "
			+ "and (:includeDuplicates = true or be.activity.primaryActivity is null) "
			+ "and be.date >= :since order by be.window asc, be.value desc")
	List<BestEffort> findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(
			@Param("athleteId") String athleteId, @Param("kind") BestEffortKind kind,
			@Param("since") LocalDate since, @Param("includeDuplicates") boolean includeDuplicates);

	@Query("select be from BestEffort be where be.athlete.id = :athleteId and be.kind = :kind "
			+ "and (:includeDuplicates = true or be.activity.primaryActivity is null) "
			+ "order by be.window asc, be.value desc")
	List<BestEffort> findByAthleteIdAndKindOrderByWindowAscValueDesc(
			@Param("athleteId") String athleteId, @Param("kind") BestEffortKind kind,
			@Param("includeDuplicates") boolean includeDuplicates);

	/** Used by {@code BestEffortController#activityBestEffortRanks} to find which (kind, window)
	 * pairs a caller-supplied batch of activities actually holds a row for - see its Javadoc. */
	List<BestEffort> findByAthleteIdAndActivityIdIn(String athleteId, List<String> activityIds);

	void deleteByAthleteIdAndKindAndActivityId(String athleteId, BestEffortKind kind, String activityId);

	void deleteByAthleteIdAndKind(String athleteId, BestEffortKind kind);

	void deleteByAthleteId(String athleteId);
}
