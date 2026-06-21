package com.cadence.api.athletes;

import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.athletes.dto.AthleteUpdateResponse;
import com.cadence.api.athletes.dto.ZoneSetReplaceRequest;
import com.cadence.api.athletes.dto.ZoneSetReplaceResponse;
import com.cadence.api.athletes.dto.ZoneSetResponse;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserMapper;
import com.cadence.api.users.UserService;
import com.cadence.api.users.dto.UserResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AthleteController {

	private final UserService userService;
	private final UserMapper userMapper;
	private final AthleteService athleteService;
	private final ZoneService zoneService;
	private final FitnessService fitnessService;
	private final AccessGuard accessGuard;

	public AthleteController(UserService userService, UserMapper userMapper, AthleteService athleteService,
			ZoneService zoneService, FitnessService fitnessService, AccessGuard accessGuard) {
		this.userService = userService;
		this.userMapper = userMapper;
		this.athleteService = athleteService;
		this.zoneService = zoneService;
		this.fitnessService = fitnessService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/athletes/{id}")
	public UserResponse getAthlete(@PathVariable String id) {
		accessGuard.requireRead(id);
		return userMapper.toResponse(userService.getById(id));
	}

	@PatchMapping("/v1/athletes/{id}")
	public AthleteUpdateResponse updateAthlete(@PathVariable String id, @RequestBody AthleteUpdateRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		List<ZoneType> recomputed = athleteService.updateProfile(athlete, request);
		List<String> recomputedWire = recomputed.stream().map(ZoneType::wireValue).toList();
		return new AthleteUpdateResponse(userMapper.toResponse(athlete), recomputedWire);
	}

	@GetMapping("/v1/athletes/{id}/zones")
	public DataListResponse<ZoneSetResponse> listZones(@PathVariable String id) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		List<ZoneSetResponse> zones = zoneService.getAllOrCreate(athlete).stream()
				.map(zs -> new ZoneSetResponse(zs.getType(), zoneService.referenceFor(athlete, zs.getType()), zs.getZones()))
				.toList();
		return new DataListResponse<>(zones);
	}

	@PutMapping("/v1/athletes/{id}/zones/{type}")
	public ZoneSetReplaceResponse replaceZoneSet(@PathVariable String id, @PathVariable ZoneType type,
			@Valid @RequestBody ZoneSetReplaceRequest request) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		zoneService.replaceZones(athlete, type, request.zones());
		Double reference = zoneService.referenceFor(athlete, type);
		return new ZoneSetReplaceResponse(type, reference, true);
	}

	@GetMapping("/v1/athletes/{id}/fitness")
	public DataListResponse<FitnessPoint> getFitnessTrend(@PathVariable String id,
			@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
		accessGuard.requireRead(id);
		LocalDate effectiveTo = to != null ? to : LocalDate.now();
		LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(84);
		return new DataListResponse<>(fitnessService.computeFitnessSeries(id, effectiveFrom, effectiveTo));
	}
}
