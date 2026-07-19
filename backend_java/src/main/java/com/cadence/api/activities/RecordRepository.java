package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordRepository extends JpaRepository<Record, RecordId> {

	// record is a TimescaleDB hypertable in ~1000 time-based chunks that can't be pruned by
	// activity_id, so the per-activity FK cascade visits every chunk. Deleting an account's
	// activities through the cascade therefore means thousands of chunk-wide deletes in one
	// transaction - enough to take the database down. This clears the athlete's records in a
	// single set-based statement so the cascades afterwards find nothing left to do.
	@Modifying
	@Query(value = "DELETE FROM record WHERE activity_id IN (SELECT id FROM activity WHERE athlete_id = :athleteId)",
			nativeQuery = true)
	void deleteByAthleteId(@Param("athleteId") String athleteId);

	@Query("select r from Record r where r.id.activityId = :activityId order by r.t")
	List<Record> findByActivityIdOrderByT(@Param("activityId") String activityId);

	long countByIdActivityId(String activityId);

	boolean existsByIdActivityIdAndAirTempIsNotNull(String activityId);
}
