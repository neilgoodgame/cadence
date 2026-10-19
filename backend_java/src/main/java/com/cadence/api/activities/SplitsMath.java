package com.cadence.api.activities;

/** Pace/grade math shared by RaceSplitsService and ActivitySplitsService, kept in one place so
 * race and activity splits can't drift apart. Mirrors backend/activities/splits.py's module-
 * level pace_s_per_km/gap_pace_s_per_km/avg_grade_pct. */
public final class SplitsMath {

	private SplitsMath() {}

	// Minetti et al. 2002 ("Energy cost of walking and running at extreme uphill and downhill
	// slopes") metabolic cost of running on a grade, J/kg/m - the standard model behind GAP
	// (grade-adjusted pace). Valid for gradients within +/-45%; clamped there since a whole
	// split's *average* grade shouldn't realistically exceed that, and the polynomial isn't
	// meant to extrapolate past it.
	private static final double GAP_FLAT_COST = 3.6;
	private static final double GAP_MAX_GRADE_FRACTION = 0.45;

	public static Double paceSPerKm(double timeS, double distanceKm) {
		return distanceKm > 0 ? timeS / distanceKm : null;
	}

	private static double metabolicCost(double gradeFraction) {
		double g = gradeFraction;
		return 155.4 * Math.pow(g, 5) - 30.4 * Math.pow(g, 4) - 43.3 * Math.pow(g, 3) + 46.3 * Math.pow(g, 2) + 19.5 * g + GAP_FLAT_COST;
	}

	/** Grade-adjusted pace - the flat-equivalent pace for the same effort, from the Minetti
	 * cost-of-running model: GAP = actual pace / (cost at this grade / cost on flat ground). */
	public static Double gapPaceSPerKm(Double paceSPerKm, Double avgGradePct) {
		if (paceSPerKm == null || avgGradePct == null) return null;
		double gradeFraction = Math.max(-GAP_MAX_GRADE_FRACTION, Math.min(GAP_MAX_GRADE_FRACTION, avgGradePct / 100));
		double gapFactor = metabolicCost(gradeFraction) / GAP_FLAT_COST;
		return round1(paceSPerKm / gapFactor);
	}

	/** Net grade over a split, from its first/last recorded altitude - same formula as a course
	 * section's own avgGradePct (CourseSections._grade): end-minus-start elevation over distance,
	 * not an average of per-sample grades. Null if either end has no altitude. */
	public static Double avgGradePct(Double startAltitude, Double endAltitude, double distKm) {
		if (startAltitude == null || endAltitude == null || distKm <= 0) return null;
		return round1((endAltitude - startAltitude) / (distKm * 1000) * 100);
	}

	public static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	public static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
