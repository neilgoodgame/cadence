package com.cadence.api.activities;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityIntensityHistogramRepository extends JpaRepository<ActivityIntensityHistogram, String> {

	/** ensureHistogram's lazy first build: inserts only if no row exists yet, so two requests
	 * building the same activity's histogram at once can't collide on the primary key (seen on
	 * staging when overlapping preview_threshold_decay calls both backfilled). A plain save()
	 * would throw, and inside a caller's transaction would also abort that transaction. Returns
	 * the number of rows inserted (0 if another request got there first). */
	@Modifying
	@Query(value = "INSERT INTO activity_intensity_histogram (activity_id, power_30s, heartrate) "
			+ "VALUES (:activityId, CAST(:power30s AS jsonb), CAST(:heartrate AS jsonb)) "
			+ "ON CONFLICT (activity_id) DO NOTHING", nativeQuery = true)
	int insertIfAbsent(@Param("activityId") String activityId, @Param("power30s") String power30sJson,
			@Param("heartrate") String heartrateJson);
}
