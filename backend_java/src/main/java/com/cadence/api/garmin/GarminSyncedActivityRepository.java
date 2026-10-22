package com.cadence.api.garmin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GarminSyncedActivityRepository extends JpaRepository<GarminSyncedActivity, Long> {

	@Query("SELECT s.garminActivityId FROM GarminSyncedActivity s WHERE s.owner.id = :ownerId AND s.garminActivityId IN :ids")
	Set<Long> findSeenIds(@Param("ownerId") String ownerId, @Param("ids") Collection<Long> ids);

	List<GarminSyncedActivity> findByRunIdOrderByStartTimeAsc(String runId);

	Optional<GarminSyncedActivity> findFirstByOwnerIdOrderByStartTimeDesc(String ownerId);
}
