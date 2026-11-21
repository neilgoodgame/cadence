package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActivitySourceRepository extends JpaRepository<ActivitySource, String> {

	List<ActivitySource> findByActivityIdOrderByCreatedAtAsc(String activityId);

	// Issue #542: the swap-back candidate for detaching a currently-primary source with no
	// explicitly recorded demotedSourceId - see ActivitySourceService#previousPrimarySourceForField.
	Optional<ActivitySource> findFirstByActivityIdAndSensorKindAndOriginOrderByCreatedAtDesc(String activityId,
			String sensorKind, ActivitySourceOrigin origin);

	// Issue #542: every attached source with no Merge row at all - either a pre-PR-575 attach
	// (the handful of real activities this backfills) or, after the backfill has run, nothing -
	// see DetachMergeBackfillRunner.
	@Query("select s from ActivitySource s where s.origin = com.cadence.api.activities.ActivitySourceOrigin.ATTACHED "
			+ "and s.id not in (select m.attachedSource.id from Merge m where m.attachedSource is not null)")
	List<ActivitySource> findOrphanedAttachedSources();
}
