package com.cadence.api.workouts;

/** One flattened leaf step's average target intensity and duration - see
 * WorkoutCalculations.computeChartPreview's Javadoc. durationSeconds lets the library card's
 * mini chart size each bar proportionally to how long that interval actually is, rather than
 * every leaf getting equal width regardless of a 30-second surge vs a 20-minute steady effort. */
public record ChartPreviewPoint(double intensity, int durationSeconds) {
}
