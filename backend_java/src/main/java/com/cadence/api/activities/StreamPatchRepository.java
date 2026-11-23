package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamPatchRepository extends JpaRepository<StreamPatch, String> {

	List<StreamPatch> findByActivityIdAndField(String activityId, String field);

	List<StreamPatch> findByActivityId(String activityId);
}
