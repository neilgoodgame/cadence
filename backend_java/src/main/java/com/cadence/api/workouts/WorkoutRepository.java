package com.cadence.api.workouts;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkoutRepository extends JpaRepository<Workout, String> {

	List<Workout> findByCreatedByIdOrderByIdDesc(String createdById);

	// Export's "counts" metadata block.
	long countByCreatedById(String createdById);

	long countByCreatedByIdAndSport(String createdById, com.cadence.api.common.domain.Sport sport);

	/** The detail view walks the (lazy) step collection - fetch it eagerly here rather than in the list view. */
	@Query("select distinct w from Workout w left join fetch w.steps where w.id = :id")
	Optional<Workout> findByIdWithSteps(@Param("id") String id);

	long countByFolderId(String folderId);

	/**
	 * Single dynamic-filter/sort query backing the library screen. Only one CASE branch is
	 * non-null per row (matching :sort), so it effectively sorts by that one active criterion,
	 * falling through to updated_at DESC for "recent" and as a stable tiebreak otherwise.
	 * Native SQL (not JPQL) because tag filtering needs Postgres jsonb containment and the
	 * "used" sort needs a correlated subquery in ORDER BY - both awkward in JPQL/Specifications.
	 * CAST(... AS text) on each nullable param avoids Postgres/JDBC "could not determine data
	 * type" errors when a bind value is null.
	 */
	@Query(
			value = """
					SELECT w.* FROM workout w
					WHERE w.created_by = :athleteId
					  AND (CAST(:folderId AS text) IS NULL OR w.folder_id = :folderId)
					  AND (CAST(:tag AS text) IS NULL OR w.tags::jsonb @> to_jsonb(ARRAY[:tag]::text[]))
					  AND (CAST(:sport AS text) IS NULL OR w.sport = :sport)
					  AND (CAST(:search AS text) IS NULL OR w.name ILIKE CONCAT('%', :search, '%'))
					ORDER BY
					  CASE WHEN :sort = 'name' THEN w.name END ASC,
					  CASE WHEN :sort = 'duration' THEN w.duration END DESC,
					  CASE WHEN :sort = 'tss' THEN w.tss END DESC,
					  CASE WHEN :sort = 'used' THEN (SELECT COUNT(*) FROM scheduled_workout sw WHERE sw.workout_id = w.id) END DESC,
					  w.updated_at DESC
					""",
			nativeQuery = true)
	List<Workout> findFiltered(
			@Param("athleteId") String athleteId,
			@Param("folderId") String folderId,
			@Param("tag") String tag,
			@Param("sport") String sport,
			@Param("search") String search,
			@Param("sort") String sort);
}
