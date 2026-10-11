package com.cadence.api.courses;

/** One climb segment detected on a course - see CourseProcessing.detectClimbs. */
public record Climb(double startKm, double endKm, int gainM, double avgGradePct) {
}
