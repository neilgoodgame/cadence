package com.cadence.api.activities;

import java.util.List;

/** The fixed window/distance tables that best-effort tracking checks every upload against. */
public final class BestEffortWindows {

	public record PowerWindow(String label, int seconds) {
	}

	public record PaceDistance(String label, double km) {
	}

	public static final List<Integer> POWER_CURVE_DURATIONS = List.of(5, 15, 30, 60, 300, 600, 1200, 3600);
	public static final List<Integer> HR_CURVE_DURATIONS = List.of(60, 300, 600, 1200, 3600);

	public static final List<PowerWindow> POWER_BEST_EFFORT_WINDOWS = List.of(
			new PowerWindow("5s", 5),
			new PowerWindow("15s", 15),
			new PowerWindow("30s", 30),
			new PowerWindow("1min", 60),
			new PowerWindow("5min", 300),
			new PowerWindow("10min", 600),
			new PowerWindow("20min", 1200),
			new PowerWindow("60min", 3600));

	public static final List<PaceDistance> PACE_BEST_EFFORT_DISTANCES_KM = List.of(
			new PaceDistance("1km", 1.0),
			new PaceDistance("5km", 5.0),
			new PaceDistance("10km", 10.0),
			new PaceDistance("half_marathon", 21.0975),
			new PaceDistance("30km", 30.0),
			new PaceDistance("marathon", 42.195),
			new PaceDistance("50km", 50.0));

	private BestEffortWindows() {
	}
}
