package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivitySourceRepository extends JpaRepository<ActivitySource, String> {

	List<ActivitySource> findByActivityIdOrderByCreatedAtAsc(String activityId);

	// Issue #542: the swap-back candidate for detaching a currently-primary source with no
	// explicitly recorded demotedSourceId - see ActivitySourceService#previousPrimarySourceForField.
	Optional<ActivitySource> findFirstByActivityIdAndSensorKindAndOriginOrderByCreatedAtDesc(String activityId,
			String sensorKind, ActivitySourceOrigin origin);
}
