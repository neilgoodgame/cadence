package com.cadence.api.activities.calc;

import com.cadence.api.common.domain.Sport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** The steady-session qualification table from the Aerobic decoupling &amp; durability spec,
 * plus the warm-up-trim helper that produces the "steady window" it's applied to. Mirrors the
 * Python backend's check_decoupling_qualification/_steady_window_start_index exactly - see
 * uploads/processing.py there for the shared rationale. */
public final class DecouplingQualificationCalculator {

	public static final int WARMUP_SECONDS = 600;
	// VI/IF limit and minimum steady minutes are athlete-configurable (User.
	// decoupleViLimitBike/Run/decouplingIfLimit/decouplingMinSteadyMinutes) - these are just
	// the defaults new athletes get, matching the design spec's own fixed numbers. Not read
	// directly by checkQualification below; ActivityDecouplingService resolves the athlete's
	// actual values and passes them in explicitly.
	public static final int MIN_STEADY_SECONDS = 3600;
	public static final double IF_LIMIT = 0.85;
	public static final double HR_COVERAGE_MIN = 0.90;
	public static final double POWER_COVERAGE_MIN = 0.95;
	public static final Map<Sport, Double> VI_LIMIT = Map.of(Sport.BIKE, 1.06, Sport.RUN, 1.04);

	public record Result(
			boolean qualified, List<String> reasons, Double vi, Double ifValue, int steadySeconds,
			double hrCoverage, double powerCoverage) {}

	/** First record index where "active" elapsed time (a recording gap of any length
	 * contributes at most 1s, same convention LapDerivationService already uses for pause
	 * handling, so a stop of any length removes itself from "steady window moving time" simply
	 * by having no Record rows - no separate "stops >= 5 min" filter needed on top of this) has
	 * advanced >= WARMUP_SECONDS past the activity's start. null if the recording never reaches
	 * that - too short to ever qualify. */
	public static Integer steadyWindowStartIndex(List<Integer> tSeries) {
		if (tSeries.isEmpty()) {
			return null;
		}
		int active = tSeries.get(0);
		int t0 = tSeries.get(0);
		for (int i = 1; i < tSeries.size(); i++) {
			active += Math.min(tSeries.get(i) - tSeries.get(i - 1), 1);
			if (active - t0 >= WARMUP_SECONDS) {
				return i;
			}
		}
		return null;
	}

	/** viLimit/ifLimit/minSteadySeconds are the athlete's own configured thresholds (User.
	 * decouplingViLimitBike/Run/decouplingIfLimit/decouplingMinSteadyMinutes) - the caller
	 * (ActivityDecouplingService) resolves which one applies; this method doesn't read athlete
	 * state directly, keeping it a pure function of its arguments like the rest of this class. */
	public static Result checkQualification(Sport sport, List<Integer> powerSeries, List<Integer> hrSeries, Double threshold,
			double viLimit, double ifLimit, int minSteadySeconds) {
		List<String> reasons = new ArrayList<>();
		int steadySeconds = powerSeries.size();

		Double avgPower = mean(powerSeries);
		Double normPower = powerSeries.stream().anyMatch(Objects::nonNull) ? NormalizedPowerCalculator.compute(powerSeries) : null;
		Double vi = (normPower != null && avgPower != null && avgPower != 0) ? round2(normPower / avgPower) : null;
		if (vi == null || vi > viLimit) {
			reasons.add("variable");
		}

		Double ifValue = (normPower != null && threshold != null && threshold != 0) ? round2(normPower / threshold) : null;
		if (threshold == null) {
			reasons.add("no_threshold");
		}
		if (ifValue == null || ifValue > ifLimit) {
			if (threshold != null) {
				reasons.add("intensity");
			}
		}

		if (steadySeconds < minSteadySeconds) {
			reasons.add("short");
		}

		double hrCoverage = steadySeconds > 0 ? hrSeries.stream().filter(Objects::nonNull).count() / (double) steadySeconds : 0.0;
		if (hrCoverage < HR_COVERAGE_MIN) {
			reasons.add("hr_coverage");
		}

		double powerCoverage = steadySeconds > 0 ? powerSeries.stream().filter(Objects::nonNull).count() / (double) steadySeconds : 0.0;
		if (powerCoverage < POWER_COVERAGE_MIN) {
			reasons.add("power_coverage");
		}

		return new Result(reasons.isEmpty(), reasons, vi, ifValue, steadySeconds, hrCoverage, powerCoverage);
	}

	private static Double mean(List<Integer> values) {
		OptionalDouble avg = values.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).average();
		return avg.isPresent() ? avg.getAsDouble() : null;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private DecouplingQualificationCalculator() {
	}
}
