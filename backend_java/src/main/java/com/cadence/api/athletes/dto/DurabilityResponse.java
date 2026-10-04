package com.cadence.api.athletes.dto;

import java.time.LocalDate;
import java.util.List;

/** GET /v1/athletes/{id}/durability - see DurabilityQueryService for the assembly logic. */
public record DurabilityResponse(
		List<Session> sessions, Rolling rolling, Summary summary, Sections durability) {

	public record Session(
			String activityId, String name, LocalDate date, String sport, Double decouplingPct, Double ef,
			Double efFirst, Double efSecond, Integer steadySeconds, boolean hot, Double avgTemp, Double avgCore) {}

	public record Rolling(List<Point> ride, List<Point> run) {
		public record Point(LocalDate date, double decouplingPct, Double ef) {}
	}

	public record Summary(
			int count, Double last28Avg, Double prev28Avg, Double last28CoolAvg, Double last28Ef, Double prev28Ef) {}

	public record Sections(SportSection ride, SportSection run) {
		public record SportSection(String basis, List<Integer> thresholds, List<Integer> windows, List<Cell> cells) {}

		public record Cell(
				int windowS, int threshold, int power, Integer pctOfFresh, String activityId, String activityName,
				LocalDate date) {}
	}
}
