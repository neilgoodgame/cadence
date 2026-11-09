package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivitySourceRepository extends JpaRepository<ActivitySource, String> {

	List<ActivitySource> findByActivityIdOrderByCreatedAtAsc(String activityId);
}
