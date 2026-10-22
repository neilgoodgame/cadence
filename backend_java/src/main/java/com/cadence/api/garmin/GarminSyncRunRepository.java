package com.cadence.api.garmin;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarminSyncRunRepository extends JpaRepository<GarminSyncRun, String> {

	List<GarminSyncRun> findByOwnerIdOrderByStartedAtDesc(String ownerId, Pageable pageable);

	List<GarminSyncRun> findByOwnerIdOrderByStartedAtDesc(String ownerId);
}
