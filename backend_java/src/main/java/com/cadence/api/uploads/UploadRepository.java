package com.cadence.api.uploads;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadRepository extends JpaRepository<Upload, String> {

	/**
	 * The most recent settled (ready, has an activity) upload of this file for this athlete - the
	 * duplicate-detection key. The activity is fetched eagerly because the caller links it to a new
	 * duplicate upload whose response (serialized after the transaction ends) needs its sport.
	 */
	@EntityGraph(attributePaths = "activity")
	Optional<Upload> findFirstByAthleteIdAndFileHashAndStatusAndActivityIsNotNullOrderByReceivedAtDesc(
			String athleteId, String fileHash, UploadStatus status);

	// The fetch joins here and below keep UploadMapper (which runs outside any transaction and
	// reads the activity's sport) off uninitialized lazy proxies.
	@Query("SELECT u FROM Upload u LEFT JOIN FETCH u.activity WHERE u.id = :id")
	Optional<Upload> findByIdWithActivity(@Param("id") String id);

	@Query("SELECT u FROM Upload u LEFT JOIN FETCH u.activity WHERE u.batch.id = :batchId")
	List<Upload> findByBatchIdWithActivity(@Param("batchId") String batchId);

	long countByBatchIdAndStatusIn(String batchId, List<UploadStatus> statuses);

	@Query("SELECT u.storedPath FROM Upload u WHERE u.athlete.id = :athleteId AND (u.status <> com.cadence.api.uploads.UploadStatus.READY OR u.activity IS NULL)")
	List<String> findPurgeableStoredPathsByAthleteId(@Param("athleteId") String athleteId);

	@Modifying
	@Query("DELETE FROM Upload u WHERE u.athlete.id = :athleteId AND (u.status <> com.cadence.api.uploads.UploadStatus.READY OR u.activity IS NULL)")
	int deleteNonEssentialByAthleteId(@Param("athleteId") String athleteId);
}
