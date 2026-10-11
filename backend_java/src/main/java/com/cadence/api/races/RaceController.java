package com.cadence.api.races;

import com.cadence.api.athletes.ThresholdSuggestionService;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.races.dto.RaceCommentCreateRequest;
import com.cadence.api.races.dto.RaceCommentResponse;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.races.dto.RaceUpdateRequest;
import com.cadence.api.races.dto.SplitsResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RaceController {

	private static final Set<String> SPLITS_MODES = Set.of("laps", "1", "5");

	private final RaceService raceService;
	private final RaceBlockService raceBlockService;
	private final RaceSplitsService raceSplitsService;
	private final RaceCommentService raceCommentService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final ThresholdSuggestionService thresholdSuggestionService;

	public RaceController(RaceService raceService, RaceBlockService raceBlockService, RaceSplitsService raceSplitsService,
			RaceCommentService raceCommentService, UserService userService, AccessGuard accessGuard,
			ThresholdSuggestionService thresholdSuggestionService) {
		this.raceService = raceService;
		this.raceBlockService = raceBlockService;
		this.raceSplitsService = raceSplitsService;
		this.raceCommentService = raceCommentService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.thresholdSuggestionService = thresholdSuggestionService;
	}

	@GetMapping("/v1/races")
	public DataListResponse<RaceResponse> listRaces() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(raceService.listRaces(athleteId).stream().map(raceService::toResponse).toList());
	}

	@PostMapping("/v1/races")
	@ResponseStatus(HttpStatus.CREATED)
	public RaceResponse createRace(@Valid @RequestBody RaceCreateRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);
		RaceResponse response = raceService.toResponse(raceService.createRace(athlete, request));
		thresholdSuggestionService.invalidate(athleteId);
		return response;
	}

	@PatchMapping("/v1/races/{id}")
	public RaceResponse updateRace(@PathVariable String id, @RequestBody RaceUpdateRequest request) {
		Race race = raceService.getRace(id);
		accessGuard.requireWrite(race.getAthlete().getId());
		RaceResponse response = raceService.toResponse(raceService.updateRace(race, request));
		thresholdSuggestionService.invalidate(race.getAthlete().getId());
		return response;
	}

	@DeleteMapping("/v1/races/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRace(@PathVariable String id) {
		Race race = raceService.getRace(id);
		accessGuard.requireWrite(race.getAthlete().getId());
		String athleteId = race.getAthlete().getId();
		raceService.deleteRace(id);
		thresholdSuggestionService.invalidate(athleteId);
	}

	@GetMapping("/v1/races/{id}/block")
	public RaceBlockResponse getRaceBlock(@PathVariable String id) {
		Race race = raceService.getRace(id);
		accessGuard.requireRead(race.getAthlete().getId());
		return raceBlockService.getRaceBlock(race);
	}

	@GetMapping("/v1/races/{id}/splits")
	public SplitsResponse getRaceSplits(@PathVariable String id, @RequestParam(defaultValue = "laps") String mode) {
		Race race = raceService.getRace(id);
		accessGuard.requireRead(race.getAthlete().getId());
		if (!SPLITS_MODES.contains(mode)) {
			throw new ValidationException("Must be one of laps, 1, 5.", "mode");
		}
		return raceSplitsService.getSplits(race, mode);
	}

	/** Unlike activity comments (read-gated), races comments use the same self-or-coach-with-
	 * grant permission as the rest of this resource - see RaceCommentService's Javadoc. */
	@GetMapping("/v1/races/{id}/comments")
	public DataListResponse<RaceCommentResponse> listRaceComments(@PathVariable String id) {
		Race race = raceService.getRace(id);
		accessGuard.requireRead(race.getAthlete().getId());
		return new DataListResponse<>(raceCommentService.list(id).stream().map(raceCommentService::toResponse).toList());
	}

	@PostMapping("/v1/races/{id}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public RaceCommentResponse createRaceComment(@PathVariable String id, @Valid @RequestBody RaceCommentCreateRequest request) {
		Race race = raceService.getRace(id);
		String sub = accessGuard.requireWrite(race.getAthlete().getId());
		User author = userService.getById(sub);
		return raceCommentService.toResponse(raceCommentService.create(race, author, request.text(), request.week(), request.parentId()));
	}

	@DeleteMapping("/v1/races/{id}/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRaceComment(@PathVariable String id, @PathVariable String commentId) {
		Race race = raceService.getRace(id);
		String sub = accessGuard.requireRead(race.getAthlete().getId());
		raceCommentService.delete(id, commentId, sub);
	}
}
