package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.cql.spec.FieldMap;
import java.util.Map;

public final class ActivityFieldMap implements FieldMap {

	private static final Map<String, String> FIELDS = Map.of(
			"hr", "avgHr",
			"maxhr", "maxHr",
			"tss", "tss",
			"distance", "distanceKm",
			"duration", "movingTime",
			"power", "avgPower",
			"sport", "sport",
			"environment", "environment",
			"name", "name");

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
