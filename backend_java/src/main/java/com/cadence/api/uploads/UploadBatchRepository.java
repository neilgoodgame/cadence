package com.cadence.api.uploads;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadBatchRepository extends JpaRepository<UploadBatch, String> {

	@Modifying
	@Query("DELETE FROM UploadBatch b WHERE b.athlete.id = :athleteId AND (SELECT COUNT(u) FROM Upload u WHERE u.batch.id = b.id) = 0")
	int deleteOrphanedByAthleteId(@Param("athleteId") String athleteId);
}
