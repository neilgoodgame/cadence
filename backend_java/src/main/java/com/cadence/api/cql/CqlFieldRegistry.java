package com.cadence.api.cql;

import java.util.Map;
import java.util.Set;

/** The constant tables driving CQL tokenization/parsing: field names, aliases, recognized values, units, stop words. */
public final class CqlFieldRegistry {

	public record FieldSpec(boolean numeric, String label, String unit) {
	}

	public static final Map<String, FieldSpec> FIELD_SPECS = Map.ofEntries(
			Map.entry("hr", new FieldSpec(true, "avg HR", "bpm")),
			Map.entry("maxhr", new FieldSpec(true, "max HR", "bpm")),
			Map.entry("tss", new FieldSpec(true, "TSS", "")),
			Map.entry("distance", new FieldSpec(true, "distance", "km")),
			Map.entry("duration", new FieldSpec(true, "duration", "min")),
			Map.entry("power", new FieldSpec(true, "power", "W")),
			Map.entry("pace", new FieldSpec(true, "pace", "/km")),
			Map.entry("sport", new FieldSpec(false, "sport", null)),
			Map.entry("environment", new FieldSpec(false, "environment", null)),
			Map.entry("name", new FieldSpec(false, "name", null)),
			Map.entry("tag", new FieldSpec(false, "tag", null)));

	public static final Map<String, String> FIELD_ALIASES = Map.ofEntries(
			Map.entry("hr", "hr"), Map.entry("bpm", "hr"), Map.entry("heartrate", "hr"),
			Map.entry("avghr", "hr"), Map.entry("hravg", "hr"),
			Map.entry("maxhr", "maxhr"), Map.entry("hrmax", "maxhr"),
			Map.entry("tss", "tss"), Map.entry("load", "tss"), Map.entry("stress", "tss"),
			Map.entry("distance", "distance"), Map.entry("dist", "distance"), Map.entry("km", "distance"), Map.entry("kms", "distance"),
			Map.entry("duration", "duration"), Map.entry("time", "duration"), Map.entry("mins", "duration"), Map.entry("minutes", "duration"),
			Map.entry("power", "power"), Map.entry("watts", "power"), Map.entry("watt", "power"), Map.entry("np", "power"),
			Map.entry("pace", "pace"),
			Map.entry("sport", "sport"), Map.entry("type", "sport"), Map.entry("discipline", "sport"),
			Map.entry("name", "name"), Map.entry("title", "name"),
			Map.entry("environment", "environment"), Map.entry("env", "environment"),
			Map.entry("tag", "tag"));

	public static final Map<String, String> ENVIRONMENT_VALUES = Map.ofEntries(
			Map.entry("indoor", "indoor"), Map.entry("indoors", "indoor"), Map.entry("treadmill", "indoor"),
			Map.entry("trainer", "indoor"), Map.entry("turbo", "indoor"),
			Map.entry("outdoor", "outdoor"), Map.entry("outdoors", "outdoor"), Map.entry("outside", "outdoor"));

	public static final Map<String, String> SPORT_VALUES = Map.ofEntries(
			Map.entry("run", "run"), Map.entry("runs", "run"), Map.entry("running", "run"),
			Map.entry("ride", "bike"), Map.entry("rides", "bike"), Map.entry("bike", "bike"), Map.entry("bikes", "bike"),
			Map.entry("biking", "bike"), Map.entry("cycling", "bike"), Map.entry("cycle", "bike"),
			Map.entry("swim", "swim"), Map.entry("swims", "swim"), Map.entry("swimming", "swim"));

	public static final Map<String, String> UNIT_FIELDS = Map.ofEntries(
			Map.entry("bpm", "hr"), Map.entry("km", "distance"), Map.entry("kms", "distance"),
			Map.entry("w", "power"), Map.entry("watt", "power"), Map.entry("watts", "power"),
			Map.entry("tss", "tss"), Map.entry("min", "duration"), Map.entry("mins", "duration"),
			Map.entry("minute", "duration"), Map.entry("minutes", "duration"));

	public static final Set<String> STOP_WORDS = Set.of(
			"show", "all", "me", "my", "the", "that", "those", "this", "are", "a", "an",
			"find", "get", "give", "list", "where", "with", "please", "workout", "workouts",
			"activity", "activities", "session", "sessions", "of", "i", "have", "had", "did",
			"for", "to", "it", "then", "only", "any", "longer", "shorter");

	/** Resolves a raw token to its canonical field name via {@link #FIELD_ALIASES}, or itself if it's already canonical. */
	public static String fieldKey(String token) {
		String alias = FIELD_ALIASES.get(token);
		if (alias != null) {
			return alias;
		}
		return FIELD_SPECS.containsKey(token) ? token : null;
	}

	private CqlFieldRegistry() {
	}
}
