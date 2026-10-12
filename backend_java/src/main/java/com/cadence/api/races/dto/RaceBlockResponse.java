package com.cadence.api.races.dto;

import com.cadence.api.athletes.dto.DurabilityResponse;
import java.time.LocalDate;
import java.util.List;

/** GET /v1/races/{id}/block - see RaceBlockService for the assembly logic. */
public record RaceBlockResponse(
		LocalDate start, LocalDate end, int weeksRequested, String truncatedByRaceId, List<Phase> phases,
		Parent parent, List<WeekAggregate> weeks, RaceDayForm raceDayForm, Comparison comparison,
		Prediction prediction, KeySessions keySessions, Durability durability, double sweatRateLPerH,
		String sweatRateSource) {

	/** 1-based week-of-block indices into {@code weeks} above, not calendar dates. */
	public record Phase(String name, int startWeek, int endWeek) {}

	/** B/C races only - the containing A block, if any. */
	public record Parent(String raceId, int week, int of) {}

	public record WeekRace(String id, String name, String priority, Integer resultS) {}

	public record WeekAggregate(
			LocalDate weekStart, Integer tss, Integer tssRaces, Double runKm, Integer runMovingS, Integer runCount,
			Double runAvgPaceSPerKm, Double runLongKm, Integer runTss, Double rideKm, Integer rideMovingS,
			Integer rideCount, Integer rideAvgPowerW, Double rideLongKm, Integer rideTss, Double hours, Integer sessions, Double ctl, Double atl,
			boolean planned, List<WeekRace> races) {}

	public record RaceDayForm(double ctl, Double atl, Double tsb, String band, boolean projected, boolean insufficientData) {}

	/** A races only - the most recent earlier A race at the same distance (within 2%), aligned
	 * counting back from race day. */
	public record Comparison(String raceId, List<WeekAggregate> weeks) {}

	/** A run-race blocks only - the fastest Riegel prediction from a completed B/C race. */
	public record Prediction(int timeS, String fromRaceId) {}

	public record KeySessionItem(
			LocalDate date, String name, String activityId, String scheduledWorkoutId, String reason, String label,
			Double km, Integer tss) {}

	public record KeySessions(int totalCandidates, List<KeySessionItem> items) {}

	/** A races (run or bike) only. */
	public record Durability(String sport, DurabilityResponse.Sections.SportSection current,
			DurabilityResponse.Sections.SportSection comparison) {}
}
