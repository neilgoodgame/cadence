package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivitySourceRepository extends JpaRepository<ActivitySource, String> {

	// Id is a secondary sort key purely so an exact-created_at tie (e.g. a bulk import) resolves
	// the same way on every call instead of whatever arbitrary order the DB happens to return -
	// see ActivitySourceService#resolveTaggedSeries, which breaks ties on the same two columns.
	List<ActivitySource> findByActivityIdOrderByCreatedAtAscIdAsc(String activityId);

	// Batched equivalent of findByActivityIdOrderByCreatedAtAscIdAsc for a whole chunk of
	// activities at once - see ActivitySourceService#loadTaggedSeriesLookups. join fetch s.activity
	// so each source's activity id is readable (for grouping by activity) after this query's own
	// short transaction ends, the same reason ActivityStreamRepository#findForTrendByActivityIdIn
	// fetches its own relations.
	@Query("select s from ActivitySource s join fetch s.activity where s.activity.id in :activityIds "
			+ "order by s.createdAt asc, s.id asc")
	List<ActivitySource> findByActivityIdInOrderByCreatedAtAscIdAsc(@Param("activityIds") List<String> activityIds);

	// Issue #542: the swap-back candidate for detaching a currently-primary source with no
	// explicitly recorded demotedSourceId - see ActivitySourceService#previousPrimarySourceForField.
	// Id is a secondary sort key for the same tie-break reason as findByActivityIdOrderByCreatedAtAscIdAsc.
	Optional<ActivitySource> findFirstByActivityIdAndSensorKindAndOriginOrderByCreatedAtDescIdDesc(String activityId,
			String sensorKind, ActivitySourceOrigin origin);

	// Issue #542: every attached source with no Merge row at all - either a pre-PR-575 attach
	// (the handful of real activities this backfills) or, after the backfill has run, nothing -
	// see DetachMergeBackfillRunner.
	@Query("select s from ActivitySource s where s.origin = com.cadence.api.activities.ActivitySourceOrigin.ATTACHED "
			+ "and s.id not in (select m.attachedSource.id from Merge m where m.attachedSource is not null)")
	List<ActivitySource> findOrphanedAttachedSources();
}
