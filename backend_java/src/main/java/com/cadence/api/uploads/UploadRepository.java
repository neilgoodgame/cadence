package com.cadence.api.uploads;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadRepository extends JpaRepository<Upload, String> {

	/** The most recent settled (ready, has an activity) upload of this file for this athlete - the duplicate-detection key. */
	Optional<Upload> findFirstByAthleteIdAndFileHashAndStatusAndActivityIsNotNullOrderByReceivedAtDesc(
			String athleteId, String fileHash, UploadStatus status);

	List<Upload> findByBatchId(String batchId);

	long countByBatchIdAndStatusIn(String batchId, List<UploadStatus> statuses);
}
