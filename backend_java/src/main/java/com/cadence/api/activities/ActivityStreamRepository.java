package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActivityStreamRepository extends JpaRepository<ActivityStream, Long> {

	Optional<ActivityStream> findByActivityIdAndFieldAndSourceId(String activityId, String field, String sourceId);

	/** Issue #540 (phase 5): every stored stream on this activity, with each one's source fetched
	 * so its id is readable after the query's own short transaction ends (the agreement trend loads
	 * these once per activity and resolves both device tags in memory - see {@code
	 * ActivitySourceService#loadTaggedSeriesLookup}). */
	@Query("select s from ActivityStream s join fetch s.source where s.activity.id = :activityId")
	List<ActivityStream> findForTrendByActivityId(String activityId);

	/** Issue #503 (phase 2, generalized phase 3): the newest stored {@code field} stream on this
	 * activity whose source holds the given {@code sourceKind} - an activity may have been
	 * swapped back and forth more than once, snapshotting a new source each time (see {@code
	 * ActivitySourceService#swapPrimaryStream}'s own Javadoc), so this takes the most recently
	 * created match rather than an arbitrary one. Used by {@code
	 * RunningPowerSourceRecomputeService} to find what to re-designate as primary. */
	Optional<ActivityStream> findTopByActivity_IdAndFieldAndSource_SourceKindOrderBySource_CreatedAtDesc(
			String activityId, String field, String sourceKind);
}
