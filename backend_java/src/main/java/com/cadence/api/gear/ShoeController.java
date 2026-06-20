package com.cadence.api.gear;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.gear.dto.ShoeUpdateRequest;
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
public class ShoeController {

	private final ShoeService shoeService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public ShoeController(ShoeService shoeService, UserService userService, AccessGuard accessGuard) {
		this.shoeService = shoeService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/gear/shoes")
	public DataListResponse<ShoeResponse> listShoes() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(shoeService.listShoes(athleteId).stream().map(shoeService::toResponse).toList());
	}

	@PostMapping("/v1/gear/shoes")
	@ResponseStatus(HttpStatus.CREATED)
	public ShoeResponse createShoe(@Valid @RequestBody ShoeCreateRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);
		Shoe shoe = shoeService.createShoe(athlete, request);
		return shoeService.toResponse(shoe);
	}

	@PatchMapping("/v1/gear/shoes/{id}")
	public ShoeResponse updateShoe(@PathVariable String id, @RequestBody ShoeUpdateRequest request) {
		Shoe shoe = shoeService.getShoe(id);
		accessGuard.requireWrite(shoe.getAthlete().getId());
		return shoeService.updateShoeAndRespond(id, request);
	}

	@DeleteMapping("/v1/gear/shoes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteShoe(@PathVariable String id) {
		Shoe shoe = shoeService.getShoe(id);
		accessGuard.requireWrite(shoe.getAthlete().getId());
		shoeService.deleteShoe(id);
	}
}
