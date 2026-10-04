package com.cadence.api.activities.calc;

import com.cadence.api.common.domain.Sport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Best mean-max power once tired - see ActivityDurability's own Javadoc. Unlike decoupling,
 * this doesn't need the steady-session rule (it's about fatigued bests, not aerobic drift), but
 * does gate on overall power coverage. Mirrors the Python backend's compute_durability_rows. */
public final class DurabilityCalculator {

	public static final double POWER_COVERAGE_MIN = 0.95;
	public static final List<Integer> WINDOWS_S = List.of(300, 1200, 3600);
	private static final Map<Sport, List<Integer>> THRESHOLDS = Map.of(
			Sport.BIKE, List.of(0, 1000, 2000, 3000),
			Sport.RUN, List.of(0, 60, 90));

	public record Row(String basis, int threshold, int windowS, int power, int startOffsetS) {}

	public static List<Row> computeRows(Sport sport, List<Integer> powerSeries, List<Integer> tSeries) {
		if (!THRESHOLDS.containsKey(sport) || powerSeries.stream().noneMatch(Objects::nonNull)) {
			return List.of();
		}
		int n = powerSeries.size();
		double powerCoverage = n > 0 ? powerSeries.stream().filter(Objects::nonNull).count() / (double) n : 0.0;
		if (powerCoverage < POWER_COVERAGE_MIN) {
			return List.of();
		}

		List<Integer> values = powerSeries.stream().map(p -> p != null ? p : 0).toList();
		String basis = sport == Sport.BIKE ? "kj" : "minutes";
		double[] cumulative = new double[n];
		if (sport == Sport.BIKE) {
			// Each ~1Hz sample contributes power(W) * 1s = power joules; kJ is that / 1000.
			double total = 0;
			for (int i = 0; i < n; i++) {
				total += values.get(i) / 1000.0;
				cumulative[i] = total;
			}
		}
		else {
			// Elapsed moving time, in minutes - a recording gap contributes no rows, so sample
			// index already *is* moving time (see steadyWindowStartIndex's own note on this).
			for (int i = 0; i < n; i++) {
				cumulative[i] = (i + 1) / 60.0;
			}
		}

		List<Row> rows = new ArrayList<>();
		for (int threshold : THRESHOLDS.get(sport)) {
			int reachIdx = 0;
			if (threshold > 0) {
				reachIdx = -1;
				for (int i = 0; i < n; i++) {
					if (cumulative[i] >= threshold) {
						reachIdx = i;
						break;
					}
				}
				if (reachIdx == -1) {
					continue;
				}
			}
			List<Integer> remaining = values.subList(reachIdx, n);
			for (int windowS : WINDOWS_S) {
				if (windowS > remaining.size()) {
					continue;
				}
				DurationCurveCalculator.BestWindow best = DurationCurveCalculator.bestAverageWithOffset(remaining, windowS);
				if (best == null) {
					continue;
				}
				int absoluteStart = reachIdx + best.startIndex();
				rows.add(new Row(basis, threshold, windowS, (int) Math.round(best.average()),
						tSeries.get(absoluteStart) - tSeries.get(0)));
			}
		}
		return rows;
	}

	private DurabilityCalculator() {
	}
}
