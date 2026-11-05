package com.cadence.api.activities;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

/**
 * Issue #498's overlap-detection backbone: finds pairs of an athlete's activities whose
 * recorded intervals intersect - the same ride uploaded twice from two devices (Zwift +
 * Garmin), but also any genuinely overlapping pair. Powers {@code GET /v1/activities/overlaps},
 * this issue's backfill, #501's merge candidate list, and the MCP {@code
 * list_overlapping_activities} tool.
 *
 * <p>Native SQL, not JPQL/Specifications - the join condition is Postgres's {@code tstzrange}
 * overlap operator ({@code &&}), which JPQL has no equivalent for. Every optional filter follows
 * this codebase's {@code CAST(:x AS <type>) IS NULL OR ...} convention (see
 * {@code WorkoutRepository#findFiltered}) rather than a "provided" flag + dummy value - a native
 * query binds an actual Java {@code null} as a real SQL {@code NULL}, so this works directly.
 *
 * <p>{@code anchor}/{@code other} are an arbitrary pair labelling, not "the real one" and "the
 * duplicate": when {@code activityId} is given, anchor is pinned to it; otherwise (the listing
 * mode) anchor is whichever side has the lexicographically smaller id, purely to avoid returning
 * each unordered pair twice - except when an asymmetric filter (crossSport, device vs.
 * otherDevice, otherHas) means only one direction qualifies at all, in which case that direction
 * is kept regardless of id order (see the {@code deduped} CTE below).
 *
 * <p>Duration is {@code COALESCE(elapsed_seconds, moving_time)} throughout (see issue #498:
 * moving_time understates a device's auto-paused gaps, elapsed_seconds is the better signal
 * where ingest captured it). {@code durationDeltaPct} is relative to the anchor's own duration;
 * {@code avgPowerRatio} is other/anchor, null whenever either side lacks avg_power.
 *
 * <p>No GiST index on the range expression yet (the issue allows adding one "if the query plan
 * needs it") - activity is a small, unpartitioned table per athlete, nothing like record's
 * partition-fanout problem (#500), and combining it with the athlete_id equality column would
 * need the btree_gist extension, an operational step not worth taking pre-emptively.
 */
public interface ActivityOverlapRepository extends Repository<Activity, String> {

	interface OverlapRow {
		String getAnchorId();

		String getAnchorName();

		String getAnchorSport();

		String getAnchorDevice();

		Instant getAnchorStartDate();

		Integer getAnchorElapsedSeconds();

		Integer getAnchorAvgPower();

		String getOtherId();

		String getOtherName();

		String getOtherSport();

		String getOtherDevice();

		Instant getOtherStartDate();

		Integer getOtherElapsedSeconds();

		Integer getOtherAvgPower();

		long getOverlapSeconds();

		double getOverlapPct();

		long getStartDeltaSeconds();

		Double getDurationDeltaPct();

		Double getAvgPowerRatio();

		String getStatus();
	}

	@Query(
			value = """
					WITH anchor_act AS (
					  SELECT id, sport, device, start_date, name, avg_power, primary_activity_id,
					         COALESCE(elapsed_seconds, moving_time) AS duration_s
					  FROM activity
					  WHERE athlete_id = :athleteId
					    AND (CAST(:activityId AS text) IS NULL OR id = :activityId)
					    AND (CAST(:sportWire AS text) IS NULL OR sport = :sportWire)
					    AND (CAST(:device AS text) IS NULL OR device ILIKE :device || '%')
					    AND (CAST(:environmentWire AS text) IS NULL OR environment = :environmentWire)
					    AND (CAST(:fromInstant AS timestamptz) IS NULL OR start_date >= :fromInstant)
					    AND (CAST(:toInstantExclusive AS timestamptz) IS NULL OR start_date < :toInstantExclusive)
					    AND (CAST(:minDurationSeconds AS integer) IS NULL
					         OR COALESCE(elapsed_seconds, moving_time) >= :minDurationSeconds)
					),
					other_act AS (
					  SELECT id, sport, device, start_date, name, avg_power, avg_hr, avg_cadence, avg_core_temp,
					         primary_activity_id, COALESCE(elapsed_seconds, moving_time) AS duration_s
					  FROM activity
					  WHERE athlete_id = :athleteId
					    AND (CAST(:sportWire AS text) IS NULL OR :crossSport = true OR sport = :sportWire)
					    AND (CAST(:otherDevice AS text) IS NULL OR device ILIKE :otherDevice || '%')
					    AND (CAST(:environmentWire AS text) IS NULL OR environment = :environmentWire)
					    AND (CAST(:minDurationSeconds AS integer) IS NULL
					         OR COALESCE(elapsed_seconds, moving_time) >= :minDurationSeconds)
					    AND (:requireOtherPower = false OR avg_power IS NOT NULL)
					    AND (:requireOtherHeartrate = false OR avg_hr IS NOT NULL)
					    AND (:requireOtherCadence = false OR avg_cadence IS NOT NULL)
					    AND (:requireOtherCoreTemp = false OR avg_core_temp IS NOT NULL)
					),
					pairs AS (
					  SELECT
					    a.id AS anchor_id, a.name AS anchor_name, a.sport AS anchor_sport, a.device AS anchor_device,
					    a.start_date AS anchor_start_date, a.duration_s AS anchor_elapsed_seconds, a.avg_power AS anchor_avg_power,
					    b.id AS other_id, b.name AS other_name, b.sport AS other_sport, b.device AS other_device,
					    b.start_date AS other_start_date, b.duration_s AS other_elapsed_seconds, b.avg_power AS other_avg_power,
					    EXTRACT(epoch FROM (
					      LEAST(a.start_date + a.duration_s * interval '1 second', b.start_date + b.duration_s * interval '1 second')
					      - GREATEST(a.start_date, b.start_date)
					    ))::bigint AS overlap_seconds,
					    (EXTRACT(epoch FROM (
					      LEAST(a.start_date + a.duration_s * interval '1 second', b.start_date + b.duration_s * interval '1 second')
					      - GREATEST(a.start_date, b.start_date)
					    )) / NULLIF(LEAST(a.duration_s, b.duration_s), 0) * 100)::float8 AS overlap_pct,
					    ABS(EXTRACT(epoch FROM (a.start_date - b.start_date)))::bigint AS start_delta_seconds,
					    (ABS(a.duration_s - b.duration_s)::float8 / NULLIF(a.duration_s, 0) * 100) AS duration_delta_pct,
					    (b.avg_power::float8 / NULLIF(a.avg_power, 0)) AS avg_power_ratio,
					    CASE WHEN b.primary_activity_id IS NOT DISTINCT FROM a.id
					         OR a.primary_activity_id IS NOT DISTINCT FROM b.id
					         THEN 'duplicate' ELSE 'unresolved' END AS status
					  FROM anchor_act a
					  JOIN other_act b ON b.id <> a.id
					    AND tstzrange(a.start_date, a.start_date + a.duration_s * interval '1 second')
					        && tstzrange(b.start_date, b.start_date + b.duration_s * interval '1 second')
					  WHERE :includeResolved = true
					     OR (b.primary_activity_id IS DISTINCT FROM a.id
					         AND a.primary_activity_id IS DISTINCT FROM b.id)
					),
					deduped AS (
					  -- Collapses the unordered pair's two directed rows (anchor/other swapped) into one,
					  -- canonically keeping the lower-id side as anchor - but only when BOTH directions
					  -- actually survived the CTEs above. An asymmetric filter (crossSport, device vs
					  -- otherDevice, otherHas) can make only ONE direction valid at all, in which case
					  -- there's nothing to collapse and that direction is kept regardless of id order;
					  -- deduping via a flat "anchor_id < other_id" filter instead would wrongly drop that
					  -- only-valid direction whenever the lower id happened to be the "other" side.
					  SELECT DISTINCT ON (LEAST(anchor_id, other_id), GREATEST(anchor_id, other_id)) *
					  FROM pairs
					  ORDER BY LEAST(anchor_id, other_id), GREATEST(anchor_id, other_id), anchor_id
					)
					SELECT * FROM deduped
					WHERE overlap_pct >= :minOverlapPct
					  AND (CAST(:maxStartDeltaSeconds AS bigint) IS NULL OR start_delta_seconds <= :maxStartDeltaSeconds)
					  AND (CAST(:cursorAnchorId AS text) IS NULL
					       OR (anchor_start_date, anchor_id, other_id)
					          > (CAST(:cursorStart AS timestamptz), :cursorAnchorId, :cursorOtherId))
					ORDER BY anchor_start_date, anchor_id, other_id
					LIMIT :limitPlusOne
					""",
			nativeQuery = true)
	List<OverlapRow> findOverlaps(
			@Param("athleteId") String athleteId,
			@Param("activityId") String activityId,
			@Param("sportWire") String sportWire,
			@Param("crossSport") boolean crossSport,
			@Param("device") String device,
			@Param("otherDevice") String otherDevice,
			@Param("environmentWire") String environmentWire,
			@Param("fromInstant") Instant fromInstant,
			@Param("toInstantExclusive") Instant toInstantExclusive,
			@Param("minDurationSeconds") Integer minDurationSeconds,
			@Param("requireOtherPower") boolean requireOtherPower,
			@Param("requireOtherHeartrate") boolean requireOtherHeartrate,
			@Param("requireOtherCadence") boolean requireOtherCadence,
			@Param("requireOtherCoreTemp") boolean requireOtherCoreTemp,
			@Param("includeResolved") boolean includeResolved,
			@Param("minOverlapPct") double minOverlapPct,
			@Param("maxStartDeltaSeconds") Long maxStartDeltaSeconds,
			@Param("cursorStart") Instant cursorStart,
			@Param("cursorAnchorId") String cursorAnchorId,
			@Param("cursorOtherId") String cursorOtherId,
			@Param("limitPlusOne") int limitPlusOne);
}
