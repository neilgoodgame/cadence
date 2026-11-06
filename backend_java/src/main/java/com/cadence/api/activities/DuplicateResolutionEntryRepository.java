package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DuplicateResolutionEntryRepository extends JpaRepository<DuplicateResolutionEntry, Long> {

	List<DuplicateResolutionEntry> findByBatchAndOutcome(DuplicateResolutionBatch batch, OverlapAction outcome);
}
