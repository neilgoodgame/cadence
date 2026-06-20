package com.cadence.api.gear;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.gear.dto.BikeCreateRequest;
import com.cadence.api.gear.dto.BikeDetailResponse;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.BikeUpdateRequest;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BikeController {

	private final GearService gearService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final GearMapper gearMapper;

	public BikeController(GearService gearService, UserService userService, AccessGuard accessGuard, GearMapper gearMapper) {
		this.gearService = gearService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.gearMapper = gearMapper;
	}

	@GetMapping("/v1/gear/bikes")
	public DataListResponse<BikeResponse> listBikes() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(gearService.listBikes(athleteId).stream().map(gearService::toBikeResponse).toList());
	}

	@PostMapping("/v1/gear/bikes")
	@ResponseStatus(HttpStatus.CREATED)
	public BikeResponse createBike(@Valid @RequestBody BikeCreateRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);
		Bike bike = gearService.createBike(athlete, request);
		return gearService.toBikeResponse(bike);
	}

	@GetMapping("/v1/gear/bikes/{id}")
	public BikeDetailResponse getBike(@PathVariable String id) {
		Bike bike = gearService.getBike(id);
		accessGuard.requireRead(bike.getAthlete().getId());
		var components = gearService.listComponents(id).stream().map(gearMapper::toResponse).toList();
		var summary = new BikeDetailResponse.BikeSummary(bike.getId(), bike.getAthlete().getId(), bike.getName(),
				bike.getKind(), bike.getGroupset(), bike.getDistanceKm(), bike.getHours(), bike.getRides());
		return new BikeDetailResponse(summary, components);
	}

	@PatchMapping("/v1/gear/bikes/{id}")
	public BikeResponse updateBike(@PathVariable String id, @RequestBody BikeUpdateRequest request) {
		Bike bike = gearService.getBike(id);
		accessGuard.requireWrite(bike.getAthlete().getId());
		Bike updated = gearService.updateBike(bike, request);
		return gearService.toBikeResponse(updated);
	}

	@DeleteMapping("/v1/gear/bikes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteBike(@PathVariable String id) {
		Bike bike = gearService.getBike(id);
		accessGuard.requireWrite(bike.getAthlete().getId());
		gearService.deleteBike(id);
	}
}
