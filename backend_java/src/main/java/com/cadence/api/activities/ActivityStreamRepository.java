package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityStreamRepository extends JpaRepository<ActivityStream, Long> {

	Optional<ActivityStream> findByActivityIdAndFieldAndSourceId(String activityId, String field, String sourceId);

	/** Issue #540 (phase 5): every stored stream for every activity in `activityIds` at once -
	 * one query for a whole chunk of the agreement trend's activities (see
	 * ActivitySourceService#loadTaggedSeriesLookups) instead of one per activity. join fetch on
	 * both source and activity so each stream's source id and activity id are both readable (the
	 * latter for grouping by activity) after this query's own short transaction ends. */
	@Query("select s from ActivityStream s join fetch s.source join fetch s.activity where s.activity.id in :activityIds")
	List<ActivityStream> findForTrendByActivityIdIn(@Param("activityIds") List<String> activityIds);

	/** Issue #503 (phase 2, generalized phase 3): the newest stored {@code field} stream on this
	 * activity whose source holds the given {@code sourceKind} - an activity may have been
	 * swapped back and forth more than once, snapshotting a new source each time (see {@code
	 * ActivitySourceService#swapPrimaryStream}'s own Javadoc), so this takes the most recently
	 * created match rather than an arbitrary one. Used by {@code
	 * RunningPowerSourceRecomputeService} to find what to re-designate as primary. */
	Optional<ActivityStream> findTopByActivity_IdAndFieldAndSource_SourceKindOrderBySource_CreatedAtDesc(
			String activityId, String field, String sourceKind);
}
