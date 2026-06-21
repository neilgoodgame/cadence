package com.cadence.api.activities;

import com.cadence.api.activities.dto.StreamsResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Parallel-array 1 Hz streams, downsampled by resolution and filtered to the requested channels. */
@Service
public class StreamService {

	private static final Set<String> SCALAR_FIELDS = Set.of(
			"time", "power", "heartrate", "cadence", "altitude", "distance", "speed",
			"air_temp", "humidity", "core_temp", "skin_temp", "heat_strain");

	private final RecordRepository recordRepository;

	public StreamService(RecordRepository recordRepository) {
		this.recordRepository = recordRepository;
	}

	public StreamsResponse getStreams(Activity activity, String fieldsParam, String resolution) {
		List<Record> records = recordRepository.findByActivityIdOrderByT(activity.getId());
		int step = switch (resolution) {
			case "medium" -> 5;
			case "low" -> 15;
			default -> 1;
		};
		List<Record> sampled = new ArrayList<>();
		for (int i = 0; i < records.size(); i += step) {
			sampled.add(records.get(i));
		}

		Set<String> requestedFields = (fieldsParam == null || fieldsParam.isBlank())
				? defaultFields(activity.isHasGps())
				: new LinkedHashSet<>(List.of(fieldsParam.split(",")));

		Map<String, Object> fields = new LinkedHashMap<>();
		for (String field : requestedFields) {
			if ("latlng".equals(field)) {
				if (activity.isHasGps()) {
					fields.put("latlng", sampled.stream().map(r -> List.of(r.getLat(), r.getLng())).toList());
				}
				continue;
			}
			if (!SCALAR_FIELDS.contains(field)) {
				continue;
			}
			fields.put(field, extractValues(sampled, field));
		}
		return new StreamsResponse(resolution, fields);
	}

	private Set<String> defaultFields(boolean hasGps) {
		Set<String> fields = new LinkedHashSet<>(SCALAR_FIELDS);
		if (hasGps) {
			fields.add("latlng");
		}
		return fields;
	}

	private List<?> extractValues(List<Record> records, String field) {
		return switch (field) {
			case "time" -> records.stream().map(Record::getT).toList();
			case "power" -> records.stream().map(Record::getPower).toList();
			case "heartrate" -> records.stream().map(Record::getHeartrate).toList();
			case "cadence" -> records.stream().map(Record::getCadence).toList();
			case "altitude" -> records.stream().map(Record::getAltitude).toList();
			case "distance" -> records.stream().map(Record::getDistanceKm).toList();
			case "speed" -> records.stream().map(Record::getSpeed).toList();
			case "air_temp" -> records.stream().map(Record::getAirTemp).toList();
			case "humidity" -> records.stream().map(Record::getHumidity).toList();
			case "core_temp" -> records.stream().map(Record::getCoreTemp).toList();
			case "skin_temp" -> records.stream().map(Record::getSkinTemp).toList();
			case "heat_strain" -> records.stream().map(Record::getHeatStrain).toList();
			default -> List.of();
		};
	}
}
