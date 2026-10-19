package com.cadence.api.mcp;

import com.cadence.api.common.error.ValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unit parsing shared by MCP tools that take a human-typed distance (WorkoutWriteTools'
 * create_workout, and the activity/race custom-splits tools) - mirrors the Python backend's
 * core/units.py. */
public final class McpUnits {

	private McpUnits() {}

	private static final Pattern DISTANCE_PATTERN =
			Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(m|km|mi|meters?|kilometers?|miles?)\\s*$");
	private static final double METERS_PER_KM = 1000.0;
	private static final double METERS_PER_MILE = 1609.344;

	/** Requires an explicit unit rather than defaulting a bare number to metres - there's no
	 * valid interpretation of a bare number to silently accept. */
	public static Integer parseDistanceMeters(String distance, String field) {
		if (distance == null || distance.isBlank()) {
			return null;
		}
		Matcher matcher = DISTANCE_PATTERN.matcher(distance);
		if (!matcher.matches()) {
			throw new ValidationException(
					field + " must be a number with a unit, e.g. \"400m\", \"5km\", or \"3.1mi\".", field);
		}
		double magnitude = Double.parseDouble(matcher.group(1));
		String unit = matcher.group(2).toLowerCase();
		double meters;
		if (unit.equals("m") || unit.startsWith("meter")) {
			meters = magnitude;
		}
		else if (unit.equals("km") || unit.startsWith("kilomet")) {
			meters = magnitude * METERS_PER_KM;
		}
		else {
			meters = magnitude * METERS_PER_MILE;
		}
		return (int) Math.round(meters);
	}
}
