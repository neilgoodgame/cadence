package com.cadence.api.activities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DuplicateResolutionBatchRepository extends JpaRepository<DuplicateResolutionBatch, String> {

	Optional<DuplicateResolutionBatch> findByIdAndAthleteId(String id, String athleteId);

	boolean existsByAthleteId(String athleteId);
}
