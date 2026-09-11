package com.cadence.api.activities.dto;

import com.cadence.api.workouts.PowerUnit;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.TargetType;

/** step* fields are flattened directly onto the lap (rather than a nested workoutStep object)
 * so the frontend can render step context without an N+1 workout-step lookup per lap. Null
 * whenever workoutStepId is null (an "original"-sourced lap, an unmatched activity, or an
 * unattributed trailing/leading segment - see LapDerivationService). stepDuration/stepDistance
 * are the step's own planned duration/distance - distinct from this lap's duration/distanceKm,
 * which are what was actually recorded - so the frontend can tell two laps with an identical
 * target apart when they're genuinely different steps (e.g. a 20s 100%-FTP block vs a 600s
 * 100%-FTP block). */
public record LapResponse(
		int index, int duration, double distanceKm, Integer avgHr, Integer avgPower,
		Long workoutStepId, Integer repeatIndex,
		StepKind stepKind, TargetType stepTargetType, Double stepTargetLow, Double stepTargetHigh,
		PowerUnit stepPowerUnit, Integer stepDuration, Integer stepDistance) {
}
