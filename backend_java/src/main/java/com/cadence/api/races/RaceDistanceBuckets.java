package com.cadence.api.races;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors backend/core/race_distances.py - standard race distances this app recognizes for
 * bucket-based training-block-length defaults (RaceBlockService.resolveBlockWeeks). */
public final class RaceDistanceBuckets {

	public record Bucket(String key, String label, Double km) {}

	/** km is null for "ultra" - the open-ended catch-all for anything past marathon distance. */
	public static final List<Bucket> BUCKETS = List.of(
			new Bucket("5k", "5K", 5.0),
			new Bucket("10k", "10K", 10.0),
			new Bucket("10mi", "10 Mile", 16.0934),
			new Bucket("half", "Half Marathon", 21.0975),
			new Bucket("marathon", "Marathon", 42.195),
			new Bucket("ultra", "Ultra", null));

	public static Map<String, Integer> defaultRaceBlockWeekDefaults() {
		Map<String, Integer> defaults = new LinkedHashMap<>();
		defaults.put("5k", 8);
		defaults.put("10k", 10);
		defaults.put("10mi", 12);
		defaults.put("half", 12);
		defaults.put("marathon", 16);
		defaults.put("ultra", 20);
		return defaults;
	}

	private RaceDistanceBuckets() {}
}
