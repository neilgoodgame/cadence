package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.cql.spec.FieldMap;
import java.util.Map;

public final class ActivityFieldMap implements FieldMap {

	// Map.of tops out at 10 pairs (20 args) - Map.ofEntries has no such limit.
	private static final Map<String, String> FIELDS = Map.ofEntries(
			Map.entry("date", "startDate"),
			Map.entry("hr", "avgHr"),
			Map.entry("maxhr", "maxHr"),
			Map.entry("tss", "tss"),
			Map.entry("distance", "distanceKm"),
			Map.entry("duration", "movingTime"),
			Map.entry("power", "avgPower"),
			Map.entry("temperature", "avgAirTemp"),
			Map.entry("humidity", "avgHumidity"),
			Map.entry("sport", "sport"),
			Map.entry("environment", "environment"),
			Map.entry("name", "name"));

	@Override
	public String resolve(String cqlField) {
		return FIELDS.get(cqlField);
	}

	@Override
	public double transformValue(String cqlField, double rawValue) {
		// The CQL field spec documents `duration` in minutes; Activity.movingTime is seconds.
		return "duration".equals(cqlField) ? rawValue * 60 : rawValue;
	}

	@Override
	public Object coerceValue(String cqlField, Object rawValue) {
		if ("sport".equals(cqlField)) {
			return Sport.valueOf(((String) rawValue).toUpperCase());
		}
		if ("environment".equals(cqlField)) {
			return Environment.valueOf(((String) rawValue).toUpperCase());
		}
		return rawValue;
	}
}
