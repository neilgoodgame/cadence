package com.cadence.api.activities;

import com.cadence.api.activities.calc.DropoutDetector;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.Shoe;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #501 (phase 1, part 2): applies a merge's selected fields from a source activity onto a
 * target, given an already-resolved time offset (see {@code ActivityMergeAlignmentCalculator})
 * and null policy. {@link #previewMergeFields} runs the exact same computation {@link
 * #applyMergeFields} does without writing anything, mirroring {@code
 * ActivityOverlapDetectionService}'s previewResolution/resolvePair split - a preview and the
 * real apply can never disagree on what would happen. No API endpoint wires this up yet; that's
 * a separate PR, as is the {@code merge} audit record. Mirrors backend's activities/merge_apply.py
 * - keep the two in sync when changing either.
 *
 * <p>Derived summaries (avg/NP/IF/TSS, max values, TRIMP, etc.) are never touched here -
 * recomputing them from the rewritten streams is the shared post-processing chain's job, not
 * this service's.
 */
@Service
public class ActivityMergeApplyService {

	/** Wire value is lowercase ({@code overwrite}/{@code keep_original}) - {@code
	 * property-naming-strategy: SNAKE_CASE} (application.yml) only renames JSON property keys,
	 * not enum constant values, so this needs its own @JsonValue/@JsonCreator pair just like
	 * every other wire-facing enum in this codebase (OverlapHandling, LapSource, ...). Missing
	 * this 400'd every real POST .../merge request, caught by
	 * ActivityMergeControllerIntegrationTest - a NullPolicy passed directly between Java callers
	 * (as every other test in this module does) never exercises Jackson's (de)serialization at
	 * all, so it wouldn't have caught this either. */
	public enum NullPolicy {
		OVERWRITE, KEEP_ORIGINAL;

		@JsonValue
		public String wireValue() {
			return name().toLowerCase();
		}

		@JsonCreator
		public static NullPolicy fromWireValue(String value) {
			return NullPolicy.valueOf(value.toUpperCase());
		}
	}

	/** Issue #541: how a literal 0 W reading from the source is treated when it's confirmed as a
	 * power-meter dropout (see {@code DropoutDetector}), not real data. {@code AS_NULL} (the
	 * default) masks it as though the source had no value there at all, so nullPolicy's own
	 * KEEP_ORIGINAL/OVERWRITE choice then applies uniformly; {@code KEEP} preserves today's
	 * pre-#541 behavior of copying it through like any other reading. Power-only - no other
	 * field can read a dropout-indistinguishable-from-real-effort 0 the way a power meter can. */
	public enum ZeroPolicy {
		KEEP, AS_NULL;

		@JsonValue
		public String wireValue() {
			return name().toLowerCase();
		}

		@JsonCreator
		public static ZeroPolicy fromWireValue(String value) {
			return ZeroPolicy.valueOf(value.toUpperCase());
		}
	}

	public record FieldRewriteCounts(int copied, int keptOriginal, int nulled, int dropoutsNulled) {

		// Issue #541: appended as a new field - callers that never deal with dropouts (every
		// non-power field) construct this the same 3-arg way they always have.
		public FieldRewriteCounts(int copied, int keptOriginal, int nulled) {
			this(copied, keptOriginal, nulled, 0);
		}
	}

	private record RecordFieldAccessor(Function<Record, Object> getter, BiConsumer<Record, Object> setter) {
	}

	private record ActivityFieldAccessor(Function<Activity, Object> getter, BiConsumer<Activity, Object> setter) {
	}

	// Per-second channels on Record - copying any of these rewrites the target's existing rows
	// (the target's timeline is authoritative; no new rows are ever created or deleted).
	private static final Map<String, RecordFieldAccessor> RECORD_FIELDS = Map.ofEntries(
			Map.entry("power", new RecordFieldAccessor(Record::getPower, (r, v) -> r.setPower((Integer) v))),
			Map.entry("heartrate", new RecordFieldAccessor(Record::getHeartrate, (r, v) -> r.setHeartrate((Integer) v))),
			Map.entry("cadence", new RecordFieldAccessor(Record::getCadence, (r, v) -> r.setCadence((Integer) v))),
			Map.entry("altitude", new RecordFieldAccessor(Record::getAltitude, (r, v) -> r.setAltitude((Double) v))),
			Map.entry("lat", new RecordFieldAccessor(Record::getLat, (r, v) -> r.setLat((Double) v))),
			Map.entry("lng", new RecordFieldAccessor(Record::getLng, (r, v) -> r.setLng((Double) v))),
			Map.entry("speed", new RecordFieldAccessor(Record::getSpeed, (r, v) -> r.setSpeed((Double) v))),
			Map.entry("distance_km", new RecordFieldAccessor(Record::getDistanceKm, (r, v) -> r.setDistanceKm((Double) v))),
			Map.entry("air_temp", new RecordFieldAccessor(Record::getAirTemp, (r, v) -> r.setAirTemp((Double) v))),
			Map.entry("humidity", new RecordFieldAccessor(Record::getHumidity, (r, v) -> r.setHumidity((Integer) v))),
			Map.entry("skin_temp", new RecordFieldAccessor(Record::getSkinTemp, (r, v) -> r.setSkinTemp((Double) v))),
			Map.entry("core_temp", new RecordFieldAccessor(Record::getCoreTemp, (r, v) -> r.setCoreTemp((Double) v))),
			Map.entry("heat_strain", new RecordFieldAccessor(Record::getHeatStrain, (r, v) -> r.setHeatStrain((Double) v))));

	// Single-value, user-editable fields on Activity - a straight copy, no time offset or null
	// policy involved (those only matter for a per-second stream).
	private static final Map<String, ActivityFieldAccessor> ACTIVITY_FIELDS = Map.of(
			"name", new ActivityFieldAccessor(Activity::getName, (a, v) -> a.setName((String) v)),
			"avg_left_balance_pct", new ActivityFieldAccessor(Activity::getAvgLeftBalancePct, (a, v) -> a.setAvgLeftBalancePct((Double) v)),
			"start_weight_kg", new ActivityFieldAccessor(Activity::getStartWeightKg, (a, v) -> a.setStartWeightKg((Double) v)),
			"end_weight_kg", new ActivityFieldAccessor(Activity::getEndWeightKg, (a, v) -> a.setEndWeightKg((Double) v)),
			"fluids_ml", new ActivityFieldAccessor(Activity::getFluidsMl, (a, v) -> a.setFluidsMl((Integer) v)),
			"bike_id", new ActivityFieldAccessor(Activity::getBike, (a, v) -> a.setBike((Bike) v)),
			"shoe_id", new ActivityFieldAccessor(Activity::getShoe, (a, v) -> a.setShoe((Shoe) v)));

	// Any field name outside RECORD_FIELDS/ACTIVITY_FIELDS is silently ignored rather than
	// throwing here - ActivityMergeService validates the request against isKnownField before
	// ever calling applyMergeFields/previewMergeFields.

	/** Issue #501 (phase 1, part 4): the whitelist ActivityMergeService validates a merge
	 * request's field list against. */
	public static boolean isKnownField(String field) {
		return RECORD_FIELDS.containsKey(field) || ACTIVITY_FIELDS.containsKey(field);
	}

	/** Issue #501 (phase 1, part 5): true for an *activity-level* field (name, fluidsMl, ...),
	 * as opposed to a per-second Record stream field - ActivityMergeService's pre-merge undo
	 * snapshot only needs the former (see Merge.previousActivityValues' own Javadoc). */
	public static boolean isActivityLevelField(String field) {
		return ACTIVITY_FIELDS.containsKey(field);
	}

	/** Issue #501 (phase 1, part 5): true for a per-second Record stream field, as opposed to
	 * an activity-level one - ActivityUnmergeService restores these by re-parsing the target's
	 * stored upload file, not from a snapshot. */
	public static boolean isStreamField(String field) {
		return RECORD_FIELDS.containsKey(field);
	}

	/** Issue #501 (phase 1, part 5): writes an already-typed value onto one Record stream
	 * field, by the same field name a merge request uses - ActivityUnmergeService's restore-
	 * from-re-parsed-file counterpart to {@link #computeStreamField}. No-op for a field outside
	 * RECORD_FIELDS. */
	public static void setRecordFieldValue(Record record, String field, Object value) {
		RecordFieldAccessor accessor = RECORD_FIELDS.get(field);
		if (accessor != null) {
			accessor.setter().accept(record, value);
		}
	}

	/** Issue #503 (phase 3): the symmetric read half of {@link #setRecordFieldValue} -
	 * {@code ActivitySourceService#attachStream}/{@code #swapPrimaryStream} use this so power/
	 * heartrate/cadence can all share one attach/swap implementation instead of three
	 * near-duplicate ones. Returns null for a field outside RECORD_FIELDS. */
	public static Object getRecordFieldValue(Record record, String field) {
		RecordFieldAccessor accessor = RECORD_FIELDS.get(field);
		return accessor == null ? null : accessor.getter().apply(record);
	}

	/** Issue #501 (phase 1, part 5): a JSON-safe snapshot of one *activity-level* field's
	 * current value, for ActivityMergeService's pre-merge undo snapshot (see Merge's own
	 * Javadoc). bikeId/shoeId return the entity's own id, not the entity - a Bike/Shoe isn't a
	 * sensible JSON value and ActivityUnmergeService re-resolves the id back to an entity
	 * itself. Returns null for a field outside ACTIVITY_FIELDS (the caller already validated
	 * the field list via isKnownField). */
	public static Object getActivityFieldValueForSnapshot(Activity activity, String field) {
		ActivityFieldAccessor accessor = ACTIVITY_FIELDS.get(field);
		if (accessor == null) {
			return null;
		}
		Object value = accessor.getter().apply(activity);
		if (value instanceof Bike bike) {
			return bike.getId();
		}
		if (value instanceof Shoe shoe) {
			return shoe.getId();
		}
		return value;
	}

	/** Issue #501 (phase 1, part 5): writes an already-resolved value onto one *activity-level*
	 * field - the inverse of {@link #getActivityFieldValueForSnapshot}, used by
	 * ActivityUnmergeService to restore a merge's snapshot. {@code value} must already be the
	 * right type for the field (a Bike/Shoe entity for bikeId/shoeId, not an id string - the
	 * caller resolves that). No-op for a field outside ACTIVITY_FIELDS. */
	public static void setActivityFieldValue(Activity activity, String field, Object value) {
		ActivityFieldAccessor accessor = ACTIVITY_FIELDS.get(field);
		if (accessor != null) {
			accessor.setter().accept(activity, value);
		}
	}

	// Issue #601: the fields in RECORD_FIELDS whose column is an integer, not a double precision
	// - bulkUpdateRecordField needs this to cast each VALUES-list parameter to the right SQL
	// type (PGJDBC can't infer it from a bare "?", including on a null value - see that method's
	// own Javadoc).
	private static final Set<String> INTEGER_RECORD_FIELDS = Set.of("power", "heartrate", "cadence", "humidity");

	// Keeps any one bulk UPDATE statement's VALUES list (and so its parameter count/text size)
	// to a sane ceiling rather than one statement per chunk-of-rows-worth of work - Postgres
	// handles a VALUES list this size without issue; this is just a round number, not tuned
	// against a real limit.
	private static final int BULK_UPDATE_CHUNK_SIZE = 2000;

	private final ActivityService activityService;
	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final EntityManager entityManager;

	public ActivityMergeApplyService(ActivityService activityService, ActivityRepository activityRepository,
			RecordRepository recordRepository, EntityManager entityManager) {
		this.activityService = activityService;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.entityManager = entityManager;
	}

	private record ComputeResult(Map<String, FieldRewriteCounts> counts, Map<String, Map<Integer, Object>> newStreamValues,
			Map<String, Object> newActivityValues, List<Record> targetRows) {
	}

	/** Issue #540 (phase 6): one inclusive {@code [startT, endT]} target-local range -
	 * {@code computeStreamField}'s {@code segments} param restricts the rewrite to seconds
	 * inside at least one of these. */
	public record Segment(int startT, int endT) {
	}

	private static boolean inSegments(int t, List<Segment> segments) {
		if (segments == null) {
			return true;
		}
		for (Segment segment : segments) {
			if (t >= segment.startT() && t <= segment.endT()) {
				return true;
			}
		}
		return false;
	}

	/** Searches, for every t the target has, the source's value at (t - offsetSeconds) -
	 * offsetSeconds is how much later the source started than the target (see
	 * ActivityMergeAlignmentCalculator's own Javadoc for the sign convention). A target second
	 * with no matching source row is treated the same as an explicit source null. dropoutTs
	 * (issue #541), when given, is a set of *source*-local seconds confirmed as power-meter
	 * dropouts - those are masked the same way as a missing source value (so nullPolicy's own
	 * branches handle them uniformly), but counted separately as dropoutsNulled rather than
	 * copied/nulled. segments (issue #540, phase 6), when given, restricts the rewrite to target
	 * seconds inside at least one of them - every other target t is left exactly as its current
	 * value, uncounted, rather than rewritten at all; this is the stream-patch use case
	 * ("rewrite target from source, restricted to certain seconds") layered onto the same
	 * rewrite a full merge already does. Package-visible (not private) since
	 * ActivityStreamPatchService, a different class in this same package, reuses it rather than
	 * duplicating this rewrite a second time. */
	static Map.Entry<Map<Integer, Object>, FieldRewriteCounts> computeStreamField(
			Map<Integer, Object> targetValues, Map<Integer, Object> sourceValues, int offsetSeconds,
			NullPolicy nullPolicy, Set<Integer> dropoutTs, List<Segment> segments) {
		Map<Integer, Object> newValues = new HashMap<>();
		int copied = 0;
		int keptOriginal = 0;
		int nulled = 0;
		int dropoutsNulled = 0;
		for (Map.Entry<Integer, Object> entry : targetValues.entrySet()) {
			int t = entry.getKey();
			if (!inSegments(t, segments)) {
				newValues.put(t, entry.getValue());
				continue;
			}
			int sourceT = t - offsetSeconds;
			if (dropoutTs != null && dropoutTs.contains(sourceT)) {
				newValues.put(t, nullPolicy == NullPolicy.KEEP_ORIGINAL ? entry.getValue() : null);
				dropoutsNulled++;
				continue;
			}
			Object sourceValue = sourceValues.get(sourceT);
			if (sourceValue != null) {
				newValues.put(t, sourceValue);
				copied++;
			}
			else if (nullPolicy == NullPolicy.KEEP_ORIGINAL) {
				newValues.put(t, entry.getValue());
				keptOriginal++;
			}
			else {
				newValues.put(t, null);
				nulled++;
			}
		}
		return Map.entry(newValues, new FieldRewriteCounts(copied, keptOriginal, nulled, dropoutsNulled));
	}

	private ComputeResult compute(Activity target, Activity source, List<String> fields, NullPolicy nullPolicy,
			int offsetSeconds, ZeroPolicy zeroPolicy) {
		Map<String, FieldRewriteCounts> counts = new LinkedHashMap<>();
		Map<String, Map<Integer, Object>> newStreamValues = new LinkedHashMap<>();
		Map<String, Object> newActivityValues = new LinkedHashMap<>();
		// Populated below only if streamFields is non-empty - applyMergeFields uses this purely
		// to evict these now-stale managed entities after its bulk native updates (see its own
		// comment), so an empty list is exactly right whenever there's nothing to evict.
		List<Record> targetRows = List.of();

		List<String> streamFields = fields.stream().filter(RECORD_FIELDS::containsKey).toList();
		if (!streamFields.isEmpty()) {
			targetRows = recordRepository.findByActivityIdOrderByT(target.getId());
			List<Record> sourceRows = recordRepository.findByActivityIdOrderByT(source.getId());
			boolean detectPowerDropouts = streamFields.contains("power") && zeroPolicy == ZeroPolicy.AS_NULL;
			for (String field : streamFields) {
				RecordFieldAccessor accessor = RECORD_FIELDS.get(field);
				// Every target row gets a value - even null, since the null policy applies to a
				// row the target never recorded this field for too.
				Map<Integer, Object> targetValues = new HashMap<>();
				for (Record row : targetRows) {
					targetValues.put(row.getT(), accessor.getter().apply(row));
				}
				// Only non-null source rows are kept here - a null source value is exactly what
				// "no matching source sample" means to computeStreamField too.
				Map<Integer, Object> sourceValues = new HashMap<>();
				for (Record row : sourceRows) {
					Object value = accessor.getter().apply(row);
					if (value != null) {
						sourceValues.put(row.getT(), value);
					}
				}
				Set<Integer> dropoutTs = null;
				if (field.equals("power") && detectPowerDropouts) {
					// Both the reference (target's power) and the cadence fallback need to be
					// keyed on the *source's* own local t, matching sourceValues - see
					// computeStreamField's own lookup (t - offsetSeconds).
					Map<Integer, Integer> sourcePower = new HashMap<>();
					for (Record row : sourceRows) {
						sourcePower.put(row.getT(), row.getPower());
					}
					Map<Integer, Integer> targetPower = new HashMap<>();
					for (Record row : targetRows) {
						targetPower.put(row.getT(), row.getPower());
					}
					// buildReference returns null (not a map of all-null values) when the target
					// has no power data at all - letting detectDropouts fall back to the cadence
					// signal instead of a reference that can never confirm anything.
					Map<Integer, Integer> reference = DropoutDetector.buildReference(targetPower, offsetSeconds,
							sourcePower.keySet());
					Map<Integer, Integer> sourceCadence = new HashMap<>();
					for (Record row : sourceRows) {
						sourceCadence.put(row.getT(), row.getCadence());
					}
					dropoutTs = DropoutDetector.detectDropouts(sourcePower, reference, sourceCadence).dropoutTs();
				}
				var result = computeStreamField(targetValues, sourceValues, offsetSeconds, nullPolicy, dropoutTs, null);
				newStreamValues.put(field, result.getKey());
				counts.put(field, result.getValue());
			}
		}

		for (String field : fields) {
			ActivityFieldAccessor accessor = ACTIVITY_FIELDS.get(field);
			if (accessor != null) {
				Object sourceValue = accessor.getter().apply(source);
				newActivityValues.put(field, sourceValue);
				counts.put(field, sourceValue != null ? new FieldRewriteCounts(1, 0, 0) : new FieldRewriteCounts(0, 0, 1));
			}
		}

		return new ComputeResult(counts, newStreamValues, newActivityValues, targetRows);
	}

	public Map<String, FieldRewriteCounts> previewMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds) {
		return previewMergeFields(targetId, sourceId, fields, nullPolicy, offsetSeconds, ZeroPolicy.AS_NULL);
	}

	/** Same computation {@link #applyMergeFields} runs, without writing anything. */
	public Map<String, FieldRewriteCounts> previewMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds, ZeroPolicy zeroPolicy) {
		Activity target = activityService.getActivity(targetId);
		Activity source = activityService.getActivity(sourceId);
		return compute(target, source, fields, nullPolicy, offsetSeconds, zeroPolicy).counts();
	}

	@Transactional
	public Map<String, FieldRewriteCounts> applyMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds) {
		return applyMergeFields(targetId, sourceId, fields, nullPolicy, offsetSeconds, ZeroPolicy.AS_NULL);
	}

	/** Writes the selected fields from source onto target - the target keeps its own id, so its
	 * comments, tags, gear, workout link and currentFrom history stay intact; only the listed
	 * fields' values change. */
	@Transactional
	public Map<String, FieldRewriteCounts> applyMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds, ZeroPolicy zeroPolicy) {
		Activity target = activityService.getActivity(targetId);
		Activity source = activityService.getActivity(sourceId);
		ComputeResult result = compute(target, source, fields, nullPolicy, offsetSeconds, zeroPolicy);

		if (!result.newStreamValues().isEmpty()) {
			for (Map.Entry<String, Map<Integer, Object>> fieldEntry : result.newStreamValues().entrySet()) {
				bulkUpdateRecordField(targetId, fieldEntry.getKey(), fieldEntry.getValue());
			}
			// compute() loaded result.targetRows() as managed entities to read their pre-merge
			// values. The bulk native updates above bypass Hibernate entirely, so without this,
			// anything later in this same transaction that re-queries these same rows as entities
			// (e.g. ActivityPostProcessingService's recompute chain, called right after this by
			// ActivityMergeService.runMerge - see its own Javadoc) would get these stale in-memory
			// values back from the persistence context's identity map instead of what's now
			// actually in the database.
			for (Record row : result.targetRows()) {
				entityManager.detach(row);
			}
		}

		if (!result.newActivityValues().isEmpty()) {
			for (Map.Entry<String, Object> entry : result.newActivityValues().entrySet()) {
				ACTIVITY_FIELDS.get(entry.getKey()).setter().accept(target, entry.getValue());
			}
			activityRepository.save(target);
		}

		return result.counts();
	}

	/** Issue #601: applyMergeFields used to reload every target row as a managed entity and save
	 * each changed one individually via recordRepository.saveAll - one JDBC round trip per row.
	 * For a multi-hour activity's full 1 Hz stream that's thousands of round trips inside one
	 * transaction - slow enough in production that a single statement partway through blew past
	 * the 30s socketTimeout (application.yml's own comment explains why that exists), which then
	 * masked the real error behind a second "Unable to rollback against JDBC Connection"
	 * exception once Hibernate tried to roll back against the now-dead connection. This issues
	 * one UPDATE per chunk of rows instead - keyed on (activity_id, t), same predicate shape as
	 * the per-row statements it replaces, so no new query plan/partition-pruning behavior - for
	 * O(rows / chunk size) round trips instead of O(rows).
	 *
	 * <p>Each VALUES-list parameter is cast explicitly (":: integer"/ "::double precision") since
	 * PGJDBC can't otherwise infer a bare "?" placeholder's type - including, critically, on a
	 * null value, which every nulled-out second (NullPolicy.OVERWRITE with no source sample)
	 * produces. Without the cast, Postgres rejects the statement with "could not determine data
	 * type of parameter".
	 *
	 * <p>Bypasses the persistence context entirely - the one caller detaches result.targetRows()
	 * immediately after calling this, for exactly that reason (see its own comment). */
	private void bulkUpdateRecordField(String activityId, String field, Map<Integer, Object> valuesByT) {
		if (valuesByT.isEmpty()) {
			return;
		}
		// Defense in depth: field is interpolated directly into the native SQL below. The only
		// caller passes a key of result.newStreamValues(), which compute() only ever populates
		// from streamFields - itself already filtered through RECORD_FIELDS.containsKey - so this
		// can never actually be false, but a future caller passing an arbitrary field name here
		// must fail loudly rather than build unvalidated SQL from it.
		if (!RECORD_FIELDS.containsKey(field)) {
			throw new IllegalArgumentException("Not a known record stream field: " + field);
		}
		String sqlType = INTEGER_RECORD_FIELDS.contains(field) ? "integer" : "double precision";
		List<Map.Entry<Integer, Object>> entries = new ArrayList<>(valuesByT.entrySet());
		for (int start = 0; start < entries.size(); start += BULK_UPDATE_CHUNK_SIZE) {
			List<Map.Entry<Integer, Object>> chunk = entries.subList(start, Math.min(start + BULK_UPDATE_CHUNK_SIZE, entries.size()));
			StringBuilder sql = new StringBuilder("UPDATE record AS r SET ").append(field).append(" = c.v FROM (VALUES ");
			for (int i = 0; i < chunk.size(); i++) {
				if (i > 0) {
					sql.append(", ");
				}
				sql.append("(?::integer, ?::").append(sqlType).append(')');
			}
			sql.append(") AS c(t, v) WHERE r.activity_id = ?::text AND r.t = c.t");
			Query query = entityManager.createNativeQuery(sql.toString());
			int position = 1;
			for (Map.Entry<Integer, Object> entry : chunk) {
				query.setParameter(position++, entry.getKey());
				query.setParameter(position++, entry.getValue());
			}
			query.setParameter(position, activityId);
			query.executeUpdate();
		}
	}
}
