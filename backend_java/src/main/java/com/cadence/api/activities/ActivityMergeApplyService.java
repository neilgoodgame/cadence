package com.cadence.api.activities;

import com.cadence.api.gear.Bike;
import com.cadence.api.gear.Shoe;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	public record FieldRewriteCounts(int copied, int keptOriginal, int nulled) {
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

	private final ActivityService activityService;
	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;

	public ActivityMergeApplyService(ActivityService activityService, ActivityRepository activityRepository,
			RecordRepository recordRepository) {
		this.activityService = activityService;
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
	}

	private record ComputeResult(Map<String, FieldRewriteCounts> counts, Map<String, Map<Integer, Object>> newStreamValues,
			Map<String, Object> newActivityValues) {
	}

	/** Searches, for every t the target has, the source's value at (t - offsetSeconds) -
	 * offsetSeconds is how much later the source started than the target (see
	 * ActivityMergeAlignmentCalculator's own Javadoc for the sign convention). A target second
	 * with no matching source row is treated the same as an explicit source null. */
	private static Map.Entry<Map<Integer, Object>, FieldRewriteCounts> computeStreamField(
			Map<Integer, Object> targetValues, Map<Integer, Object> sourceValues, int offsetSeconds, NullPolicy nullPolicy) {
		Map<Integer, Object> newValues = new HashMap<>();
		int copied = 0;
		int keptOriginal = 0;
		int nulled = 0;
		for (Map.Entry<Integer, Object> entry : targetValues.entrySet()) {
			int t = entry.getKey();
			Object sourceValue = sourceValues.get(t - offsetSeconds);
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
		return Map.entry(newValues, new FieldRewriteCounts(copied, keptOriginal, nulled));
	}

	private ComputeResult compute(Activity target, Activity source, List<String> fields, NullPolicy nullPolicy, int offsetSeconds) {
		Map<String, FieldRewriteCounts> counts = new LinkedHashMap<>();
		Map<String, Map<Integer, Object>> newStreamValues = new LinkedHashMap<>();
		Map<String, Object> newActivityValues = new LinkedHashMap<>();

		List<String> streamFields = fields.stream().filter(RECORD_FIELDS::containsKey).toList();
		if (!streamFields.isEmpty()) {
			List<Record> targetRows = recordRepository.findByActivityIdOrderByT(target.getId());
			List<Record> sourceRows = recordRepository.findByActivityIdOrderByT(source.getId());
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
				var result = computeStreamField(targetValues, sourceValues, offsetSeconds, nullPolicy);
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

		return new ComputeResult(counts, newStreamValues, newActivityValues);
	}

	/** Same computation {@link #applyMergeFields} runs, without writing anything. */
	public Map<String, FieldRewriteCounts> previewMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds) {
		Activity target = activityService.getActivity(targetId);
		Activity source = activityService.getActivity(sourceId);
		return compute(target, source, fields, nullPolicy, offsetSeconds).counts();
	}

	/** Writes the selected fields from source onto target - the target keeps its own id, so its
	 * comments, tags, gear, workout link and currentFrom history stay intact; only the listed
	 * fields' values change. */
	@Transactional
	public Map<String, FieldRewriteCounts> applyMergeFields(String targetId, String sourceId, List<String> fields,
			NullPolicy nullPolicy, int offsetSeconds) {
		Activity target = activityService.getActivity(targetId);
		Activity source = activityService.getActivity(sourceId);
		ComputeResult result = compute(target, source, fields, nullPolicy, offsetSeconds);

		if (!result.newStreamValues().isEmpty()) {
			List<Record> targetRows = recordRepository.findByActivityIdOrderByT(targetId);
			List<Record> changedRows = new ArrayList<>();
			for (Record row : targetRows) {
				boolean changed = false;
				for (Map.Entry<String, Map<Integer, Object>> fieldEntry : result.newStreamValues().entrySet()) {
					RecordFieldAccessor accessor = RECORD_FIELDS.get(fieldEntry.getKey());
					Object newValue = fieldEntry.getValue().get(row.getT());
					if (!Objects.equals(accessor.getter().apply(row), newValue)) {
						accessor.setter().accept(row, newValue);
						changed = true;
					}
				}
				if (changed) {
					changedRows.add(row);
				}
			}
			if (!changedRows.isEmpty()) {
				recordRepository.saveAll(changedRows);
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
}
