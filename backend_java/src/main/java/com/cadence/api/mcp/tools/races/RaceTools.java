package com.cadence.api.mcp.tools.races;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.McpCustomSplits;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpCustomSplitStepInput;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceBlockService;
import com.cadence.api.races.RaceComment;
import com.cadence.api.races.RaceCommentService;
import com.cadence.api.races.RaceController;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.RaceSplitsService;
import com.cadence.api.races.WeatherService;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link RaceService} the REST {@code RaceController} does. */
@Component
public class RaceTools {

	private final RaceService raceService;
	private final RaceBlockService raceBlockService;
	private final RaceSplitsService raceSplitsService;
	private final WeatherService weatherService;
	private final RaceCommentService raceCommentService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public RaceTools(RaceService raceService, RaceBlockService raceBlockService, RaceSplitsService raceSplitsService,
			WeatherService weatherService, RaceCommentService raceCommentService, UserService userService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.raceService = raceService;
		this.raceBlockService = raceBlockService;
		this.raceSplitsService = raceSplitsService;
		this.weatherService = weatherService;
		this.raceCommentService = raceCommentService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	/** Every race MCP tool only ever looks within the caller's own effective athlete scope
	 * (unlike RaceController, which also allows a coach-with-grant via a separate ownership
	 * check) - returns null for a race that doesn't exist OR belongs to someone else, so every
	 * caller treats both the same way REST's get_race_block/get_race_custom_splits already do:
	 * a generic not-found rather than a distinguishable 403. */
	private Race findOwnedRace(String raceId, String athleteId) {
		Race race;
		try {
			race = raceService.getRace(raceId);
		}
		catch (NotFoundException e) {
			return null;
		}
		return race.getAthlete().getId().equals(athleteId) ? race : null;
	}

	@McpTool(name = "list_races", description = "List the authenticated athlete's goal races - "
			+ "name, date, sport, distance, goal/result time, and any notes. Useful for planning "
			+ "training around an upcoming event.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<RaceResponse> listRaces() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return raceService.listRaces(athleteId).stream().map(raceService::toResponse).toList();
	}

	@McpTool(name = "get_race_block", description = "Get an A race's training block (weekly load/"
			+ "distance, race-day form, predicted time, key sessions) or a B/C race's 5-week window "
			+ "around it.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceBlock(String raceId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		return raceBlockService.getRaceBlock(race);
	}

	@McpTool(name = "get_race_custom_splits", description = "Get splits for a goal race's linked "
			+ "activity from your own list of steps instead of a fixed distance - e.g. \"the "
			+ "first mile, then every 2km after that\", or steps that mirror a workout's own "
			+ "structure. Each step is either end_type=time (duration_s seconds) or "
			+ "end_type=distance (distance a number with a unit, e.g. \"1mi\"/\"2km\"/\"800m\") - "
			+ "add a label to name one (e.g. \"Mile 1\"). Steps are consumed in order from the "
			+ "start of the recording, each picking up where the previous one left off, so they "
			+ "can never overlap or be out of order - a step with no recording left to consume "
			+ "(the activity ended early) is just dropped, and any recording left over after the "
			+ "last step becomes one unlabeled trailing split. Each split's delta is against the "
			+ "race's own goal pace/target power (or its activity's average, if there's no "
			+ "goal/target) - see get_activity_custom_splits for a plain activity with nothing to "
			+ "compare against.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceCustomSplits(
			@McpToolParam(description = "The race id", required = true) String raceId,
			@McpToolParam(description = "The list of steps", required = true) List<McpCustomSplitStepInput> steps) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		return raceSplitsService.customSplits(race, McpCustomSplits.convert(steps));
	}

	@McpTool(name = "get_race_splits", description = "Get splits for a goal race's linked "
			+ "activity - manual laps, fixed 1km/5km splits, or (elevation) one split per flat/"
			+ "climb/descent section of the race's linked course. Each split's delta is against "
			+ "the race's own goal pace/target power (or its activity's average, if there's no "
			+ "goal/target). mode defaults to laps; use get_race_custom_splits instead for an "
			+ "arbitrary list of steps (e.g. \"the first mile, then every 2km\").",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceSplits(
			@McpToolParam(description = "The race id", required = true) String raceId,
			@McpToolParam(description = "laps, 1, 5, or elevation - defaults to laps", required = false) String mode) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		String effectiveMode = mode != null ? mode : "laps";
		if (!RaceController.SPLITS_MODES.contains(effectiveMode)) {
			throw new ValidationException("Must be one of laps, 1, 5, elevation.", "mode");
		}
		return raceSplitsService.getSplits(race, effectiveMode);
	}

	@McpTool(name = "get_race_weather", description = "Get the race-day weather forecast, "
			+ "resolved via the race's linked course's start point. available is false (every "
			+ "other field null) if the race has no course, the course has no resolved start "
			+ "point, or the race is more than 10 days away.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object getRaceWeather(@McpToolParam(description = "The race id", required = true) String raceId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		return weatherService.forecastFor(race);
	}

	@McpTool(name = "list_race_comments", description = "List the coach/athlete plan comments on "
			+ "a goal race (from list_races), oldest first - who wrote each one, their role "
			+ "(athlete/coach), and which training week they're about, if any.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Object listRaceComments(@McpToolParam(description = "The race id", required = true) String raceId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		return raceCommentService.list(raceId).stream().map(raceCommentService::toResponse).toList();
	}

	@McpTool(name = "post_race_comment", description = "Post a plan comment on a goal race (from "
			+ "list_races) - visible to the athlete and their coach. Optionally tag it to a "
			+ "training week (1-based). Omit parent_comment_id for a top-level comment, or pass "
			+ "an existing top-level comment's id (from list_race_comments) to reply to it - "
			+ "single-level threading only, replying to a reply is rejected.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public Object postRaceComment(
			@McpToolParam(description = "The race id", required = true) String raceId,
			@McpToolParam(description = "The comment text", required = true) String text,
			@McpToolParam(description = "1-based training week, if this comment is about one", required = false) Integer week,
			@McpToolParam(description = "An existing top-level comment's id, to reply to it", required = false) String parentCommentId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		String sub = accessGuard.requireWrite(athleteId);

		Race race = findOwnedRace(raceId, athleteId);
		if (race == null) {
			return Map.of("error", "not_found");
		}
		User author = userService.getById(sub);
		RaceComment comment = raceCommentService.create(race, author, text, week, parentCommentId);
		return raceCommentService.toResponse(comment);
	}
}
