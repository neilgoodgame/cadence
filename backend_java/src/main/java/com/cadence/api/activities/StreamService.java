package com.cadence.api.activities;

import com.cadence.api.activities.calc.RunningPowerSanitizer;
import com.cadence.api.activities.dto.StreamsResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parallel-array 1 Hz streams, downsampled by resolution and filtered to the requested channels.
 * Built in a single pass over a streaming, lean-projection query (RecordRepository.StreamRow)
 * rather than loading full Record entities and re-traversing them once per requested field - see
 * StreamRow's Javadoc for why that mattered for a real OOM. */
@Service
public class StreamService {

	private static final Set<String> SCALAR_FIELDS = Set.of(
			"time", "power", "heartrate", "cadence", "altitude", "distance", "speed",
			"air_temp", "humidity", "core_temp", "skin_temp", "heat_strain");

	private final RecordRepository recordRepository;
	private final UserRepository userRepository;

	public StreamService(RecordRepository recordRepository, UserRepository userRepository) {
		this.recordRepository = recordRepository;
		this.userRepository = userRepository;
	}

	// readOnly: the underlying Stream<StreamRow> is a live DB cursor (RecordRepository.StreamRow),
	// not a pre-fetched collection, and needs a session open for as long as it's being consumed
	// below - unlike the old List<Record>-returning queries, which Spring Data JPA's own default
	// per-repository-method transaction already fully materialized before returning, needing
	// nothing from the caller. Scoped to this method only, not the controller: by the time this
	// returns, every row's been read and the cursor's closed, so the subsequent HTTP write
	// doesn't hold a DB connection open for the duration of (possibly slow) client I/O.
	@Transactional(readOnly = true)
	public StreamsResponse getStreams(Activity activity, String fieldsParam, String resolution) {
		int step = switch (resolution) {
			case "medium" -> 5;
			case "low" -> 15;
			default -> 1;
		};

		Set<String> requestedFields = (fieldsParam == null || fieldsParam.isBlank())
				? defaultFields(activity.isHasGps())
				: new LinkedHashSet<>(List.of(fieldsParam.split(",")));

		// Drop points with no position fix entirely (a brief GPS dropout mid-activity is common)
		// rather than including a null pair - List.of() throws on a null element, and a
		// [null, null] entry would be a worse contract surprise than a shorter array. If GPS
		// wasn't requested, or the activity has none, the "latlng" key is absent from the
		// response entirely rather than present-but-empty.
		boolean wantsLatlng = requestedFields.contains("latlng") && activity.isHasGps();
		boolean wantsPower = requestedFields.contains("power");
		int maxRunningPowerWatts = wantsPower ? maxRunningPowerWattsFor(activity) : 0;
		Sport sport = activity.getSport();

		Map<String, List<Object>> buffers = new LinkedHashMap<>();
		if (wantsLatlng) {
			buffers.put("latlng", new ArrayList<>());
		}
		for (String field : requestedFields) {
			if (SCALAR_FIELDS.contains(field)) {
				buffers.put(field, new ArrayList<>());
			}
		}

		try (Stream<RecordRepository.StreamRow> rows = openStream(activity.getId(), step)) {
			rows.forEach(row -> {
				for (Map.Entry<String, List<Object>> entry : buffers.entrySet()) {
					entry.getValue().add(extractOne(entry.getKey(), row, sport, maxRunningPowerWatts));
				}
			});
		}
		// latlng skips rows with no fix rather than adding a value per row above, so it needs its
		// own pass-through - every other field adds exactly one value per row by construction.
		if (wantsLatlng) {
			buffers.put("latlng", buffers.get("latlng").stream().filter(v -> v != null).toList());
		}

		return new StreamsResponse(resolution, new LinkedHashMap<>(buffers));
	}

	private Stream<RecordRepository.StreamRow> openStream(String activityId, int step) {
		if (step <= 1) {
			return recordRepository.streamByActivityIdOrderByT(activityId);
		}
		Integer offset = recordRepository.findMinTByActivityId(activityId);
		return offset == null ? Stream.empty() : recordRepository.streamByActivityIdDecimated(activityId, offset, step);
	}

	private Set<String> defaultFields(boolean hasGps) {
		Set<String> fields = new LinkedHashSet<>(SCALAR_FIELDS);
		if (hasGps) {
			fields.add("latlng");
		}
		return fields;
	}

	/** One field's value for one row - null for "latlng" on a row with no fix, filtered out by
	 * the caller afterwards rather than skipped here, so every buffer gets exactly one entry per
	 * row during the single pass (simpler than conditionally skipping mid-iteration). */
	private Object extractOne(String field, RecordRepository.StreamRow row, Sport sport, int maxRunningPowerWatts) {
		return switch (field) {
			case "latlng" -> row.getLat() != null && row.getLng() != null
					? List.of(row.getLat(), row.getLng())
					: null;
			case "time" -> row.getT();
			case "power" -> RunningPowerSanitizer.sanitizeOne(row.getPower(), sport, maxRunningPowerWatts);
			case "heartrate" -> row.getHeartrate();
			case "cadence" -> row.getCadence();
			case "altitude" -> row.getAltitude();
			case "distance" -> row.getDistanceKm();
			case "speed" -> row.getSpeed();
			case "air_temp" -> row.getAirTemp();
			case "humidity" -> row.getHumidity();
			case "core_temp" -> row.getCoreTemp();
			case "skin_temp" -> row.getSkinTemp();
			case "heat_strain" -> row.getHeatStrain();
			default -> null;
		};
	}

	/** Only called for the "power" field, and only ever meaningfully used for RUN (see
	 * RunningPowerSanitizer) - so a 1000W default on a lookup miss is inert either way, not a
	 * real fallback value being applied. */
	private int maxRunningPowerWattsFor(Activity activity) {
		return userRepository.findById(activity.getAthlete().getId())
				.map(User::getMaxRunningPowerWatts)
				.orElse(1000);
	}
}
