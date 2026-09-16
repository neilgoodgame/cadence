package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordRepository extends JpaRepository<Record, RecordId> {

	// record is range-partitioned on ts into ~126 monthly partitions that can't be pruned by
	// activity_id, so the per-activity FK cascade visits every partition. Deleting an account's
	// activities through the cascade therefore means dozens of partition-wide deletes in one
	// transaction - enough to take the database down. This clears the athlete's records in a
	// single set-based statement so the cascades afterwards find nothing left to do.
	@Modifying
	@Query(value = "DELETE FROM record WHERE activity_id IN (SELECT id FROM activity WHERE athlete_id = :athleteId)",
			nativeQuery = true)
	void deleteByAthleteId(@Param("athleteId") String athleteId);

	@Query("select r from Record r where r.id.activityId = :activityId order by r.t")
	List<Record> findByActivityIdOrderByT(@Param("activityId") String activityId);

	@Query("select min(r.t) from Record r where r.id.activityId = :activityId")
	Integer findMinTByActivityId(@Param("activityId") String activityId);

	/** Decimated in the query itself rather than loading every 1Hz record and slicing in
	 * application code - a multi-hour activity's full record set is large enough that several of
	 * these firing concurrently (e.g. expanding zone bars on the Activities list) has taken the
	 * backend OOM in production. offset anchors the kept-every-Nth pattern to the activity's own
	 * first sample (t may not start at 0), matching what index-based slicing over the full
	 * ordered list used to pick. */
	@Query("select r from Record r where r.id.activityId = :activityId and mod(r.t - :offset, :step) = 0 "
			+ "order by r.t")
	List<Record> findByActivityIdDecimated(
			@Param("activityId") String activityId, @Param("offset") int offset, @Param("step") int step);

	long countByIdActivityId(String activityId);

	boolean existsByIdActivityIdAndAirTempIsNotNull(String activityId);

	/** One grouped-aggregate query across every matched activity at once (for
	 * WorkoutMatchService's comparison endpoint), not a per-activity loop. */
	interface ActivityAvgCoreTemp {
		String getActivityId();

		Double getAvgCoreTemp();
	}

	@Query("select r.id.activityId as activityId, avg(r.coreTemp) as avgCoreTemp from Record r "
			+ "where r.id.activityId in :activityIds group by r.id.activityId")
	List<ActivityAvgCoreTemp> findAvgCoreTempByActivityIdIn(@Param("activityIds") List<String> activityIds);
}
