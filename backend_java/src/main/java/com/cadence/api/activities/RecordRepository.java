package com.cadence.api.activities;

import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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

	/** Just (t, distance_km), as scalars rather than managed Record entities - for callers that
	 * scan many activities' streams inside one transaction, where every entity a full
	 * findByActivityIdOrderByT loads stays in the persistence context until the transaction ends.
	 * RaceBlockService's goal-pace scan over every run in a training block did exactly that and
	 * took the backend OOM in staging. */
	interface TimeDistance {
		int getT();

		Double getDistanceKm();
	}

	@Query("select r.t as t, r.distanceKm as distanceKm from Record r where r.id.activityId = :activityId order by r.t")
	List<TimeDistance> findTimeDistanceByActivityIdOrderByT(@Param("activityId") String activityId);

	/** Just (distance_km, altitude), as scalars - ActivitySectionsService builds an activity's
	 * elevation series from these without loading whole Record entities (see TimeDistance). */
	interface DistanceAltitude {
		Double getDistanceKm();

		Double getAltitude();
	}

	@Query("select r.distanceKm as distanceKm, r.altitude as altitude from Record r"
			+ " where r.id.activityId = :activityId and r.distanceKm is not null order by r.t")
	List<DistanceAltitude> findDistanceAltitudeByActivityIdOrderByT(@Param("activityId") String activityId);

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

	/** Every scalar stream channel, as a lean projection rather than managed Record entities -
	 * StreamService builds its parallel-array response from this instead of
	 * findByActivityIdOrderByT/findByActivityIdDecimated. A full Record entity carries Hibernate's
	 * per-entity overhead (identity-map registration, a dirty-check snapshot of every field kept
	 * alongside the loaded instance) that a read-only projection skips entirely - for a multi-hour
	 * activity's full 1 Hz record set, at every column whether or not it's actually requested,
	 * that overhead was the dominant cost of a real OOM reproduced locally (see
	 * infra/scripts/stress-test-backend.sh) hammering this endpoint at full resolution. The
	 * fetchSize hint plus a Stream<> return type keeps the JDBC driver pulling rows from Postgres
	 * incrementally too, rather than buffering the full result set before Hibernate sees any of
	 * it - both matter together, since Stream<> alone doesn't change the driver's own buffering.
	 * Must be called inside a transaction that stays open for as long as the Stream is consumed
	 * (StreamController is @Transactional for exactly this). */
	interface StreamRow {
		int getT();

		Integer getPower();

		Integer getHeartrate();

		Integer getCadence();

		Double getAltitude();

		Double getLat();

		Double getLng();

		Double getSpeed();

		Double getDistanceKm();

		Double getAirTemp();

		Integer getHumidity();

		Double getCoreTemp();

		Double getSkinTemp();

		Double getHeatStrain();
	}

	@QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "200"))
	@Query("select r.t as t, r.power as power, r.heartrate as heartrate, r.cadence as cadence, "
			+ "r.altitude as altitude, r.lat as lat, r.lng as lng, r.speed as speed, "
			+ "r.distanceKm as distanceKm, r.airTemp as airTemp, r.humidity as humidity, "
			+ "r.coreTemp as coreTemp, r.skinTemp as skinTemp, r.heatStrain as heatStrain "
			+ "from Record r where r.id.activityId = :activityId order by r.t")
	Stream<StreamRow> streamByActivityIdOrderByT(@Param("activityId") String activityId);

	@QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "200"))
	@Query("select r.t as t, r.power as power, r.heartrate as heartrate, r.cadence as cadence, "
			+ "r.altitude as altitude, r.lat as lat, r.lng as lng, r.speed as speed, "
			+ "r.distanceKm as distanceKm, r.airTemp as airTemp, r.humidity as humidity, "
			+ "r.coreTemp as coreTemp, r.skinTemp as skinTemp, r.heatStrain as heatStrain "
			+ "from Record r where r.id.activityId = :activityId and mod(r.t - :offset, :step) = 0 "
			+ "order by r.t")
	Stream<StreamRow> streamByActivityIdDecimated(
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
