package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeRepository extends JpaRepository<Merge, String> {

	List<Merge> findByTargetActivityId(String targetActivityId);
}
