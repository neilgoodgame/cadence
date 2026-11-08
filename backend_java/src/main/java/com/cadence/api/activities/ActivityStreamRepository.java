package com.cadence.api.activities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityStreamRepository extends JpaRepository<ActivityStream, Long> {

	Optional<ActivityStream> findByActivityIdAndFieldAndSourceId(String activityId, String field, String sourceId);
}
