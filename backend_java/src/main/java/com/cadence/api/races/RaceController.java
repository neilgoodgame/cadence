package com.cadence.api.races;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.races.dto.RaceUpdateRequest;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RaceController {

	private final RaceService raceService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public RaceController(RaceService raceService, UserService userService, AccessGuard accessGuard) {
		this.raceService = raceService;
		this.userService = userService;
		this.accessGuard = accessGuard;
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
		return raceService.toResponse(raceService.createRace(athlete, request));
	}

	@PatchMapping("/v1/races/{id}")
	public RaceResponse updateRace(@PathVariable String id, @RequestBody RaceUpdateRequest request) {
		Race race = raceService.getRace(id);
		accessGuard.requireWrite(race.getAthlete().getId());
		return raceService.toResponse(raceService.updateRace(race, request));
	}

	@DeleteMapping("/v1/races/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRace(@PathVariable String id) {
		Race race = raceService.getRace(id);
		accessGuard.requireWrite(race.getAthlete().getId());
		raceService.deleteRace(id);
	}
}
