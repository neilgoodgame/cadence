package com.cadence.api.garmin;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.garmin.dto.GarminWorkoutLinksResponse;
import com.cadence.api.garmin.dto.GarminWorkoutOpRequest;
import com.cadence.api.garmin.dto.GarminWorkoutOpResponse;
import com.cadence.api.garmin.dto.GarminWorkoutOpResultRequest;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GarminWorkoutPushController {

	private final GarminWorkoutPushService service;
	private final UserService userService;

	public GarminWorkoutPushController(GarminWorkoutPushService service, UserService userService) {
		this.service = service;
		this.userService = userService;
	}

	/** 202 with the queued (or merged) operation; 204 when there was nothing to do. */
	@PostMapping("/v1/garmin/workout-ops")
	public ResponseEntity<GarminWorkoutOpResponse> enqueue(@Valid @RequestBody GarminWorkoutOpRequest request) {
		User owner = userService.getById(AuthContextHolder.get().sub());
		return service.enqueue(owner, request)
				.map(op -> ResponseEntity.status(HttpStatus.ACCEPTED).body(service.toResponse(owner.getId(), op)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** {@code status}: pending (default, oldest first, with the .tcx), failed, or all (most recent 50). */
	@GetMapping("/v1/garmin/workout-ops")
	public DataListResponse<GarminWorkoutOpResponse> list(
			@RequestParam(name = "status", defaultValue = "pending") String status) {
		String ownerId = AuthContextHolder.get().sub();
		List<GarminWorkoutOpResponse> data = switch (status) {
			case "pending" -> service.pending(ownerId);
			case "failed" -> service.failed(ownerId);
			case "all" -> service.recent(ownerId, 50);
			default -> throw new com.cadence.api.common.error.ValidationException(
					"status must be pending, failed or all.", "status");
		};
		return new DataListResponse<>(data);
	}

	@PostMapping("/v1/garmin/workout-ops/{id}/result")
	public GarminWorkoutOpResponse result(@PathVariable String id,
			@Valid @RequestBody GarminWorkoutOpResultRequest request) {
		User owner = userService.getById(AuthContextHolder.get().sub());
		return service.toResponse(owner.getId(), service.recordResult(owner, id, request));
	}

	@DeleteMapping("/v1/garmin/workout-ops/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void discard(@PathVariable String id) {
		service.discard(userService.getById(AuthContextHolder.get().sub()), id);
	}

	@GetMapping("/v1/garmin/workout-links")
	public GarminWorkoutLinksResponse links() {
		return service.links(AuthContextHolder.get().sub());
	}
}
