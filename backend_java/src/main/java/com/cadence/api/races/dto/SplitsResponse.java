package com.cadence.api.races.dto;

import java.util.List;

/** GET /v1/races/{id}/splits - see RaceSplitsService. */
public record SplitsResponse(boolean hasLaps, String ref, Double refPaceSPerKm, Halves halves, List<Split> splits) {

	public record Halves(int firstS, int secondS) {}

	public record Split(
			String label, double startKm, double endKm, int timeS, Double paceSPerKm, Integer avgHr, Double deltaVsRefSPerKm) {}
}
