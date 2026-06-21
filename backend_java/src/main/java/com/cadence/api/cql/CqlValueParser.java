package com.cadence.api.cql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a bare value token with an optional unit suffix into a numeric value plus the field that unit implies. */
final class CqlValueParser {

	private static final Pattern VALUE_RE = Pattern.compile("^([\\d.]+)\\s*([a-z%/]*)$");
	private static final Pattern PACE_RE = Pattern.compile("^\\d+:\\d{2}$");
	private static final Pattern NON_ALPHA_RE = Pattern.compile("[^a-z]");

	record Parsed(Double value, String field) {
	}

	/** {@code "M:SS"} or {@code "H:MM:SS"} -> total seconds. */
	static int parseT(String value) {
		String[] parts = value.split(":");
		int[] nums = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			nums[i] = Integer.parseInt(parts[i]);
		}
		if (nums.length == 3) {
			return nums[0] * 3600 + nums[1] * 60 + nums[2];
		}
		return nums[0] * 60 + nums[1];
	}

	static Parsed parseValue(String v) {
		if (v == null) {
			return new Parsed(null, null);
		}
		if (PACE_RE.matcher(v).matches()) {
			return new Parsed((double) parseT(v), "pace");
		}
		Matcher m = VALUE_RE.matcher(v);
		if (!m.matches()) {
			return new Parsed(null, null);
		}
		String unit = NON_ALPHA_RE.matcher(m.group(2)).replaceAll("");
		return new Parsed(Double.parseDouble(m.group(1)), CqlFieldRegistry.UNIT_FIELDS.get(unit));
	}

	static String singular(String word) {
		if (word.length() > 3 && word.endsWith("s")) {
			return word.substring(0, word.length() - 1);
		}
		return word;
	}

	private CqlValueParser() {
	}
}
