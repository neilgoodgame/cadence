package com.cadence.api.garmin;

import com.cadence.api.garmin.dto.GarminConnectionRequest;
import com.cadence.api.garmin.dto.GarminConnectionResponse;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GarminConnectionController {

	private final GarminConnectionService service;
	private final GarminSyncHistoryService history;
	private final UserService userService;

	public GarminConnectionController(GarminConnectionService service, GarminSyncHistoryService history,
			UserService userService) {
		this.service = service;
		this.history = history;
		this.userService = userService;
	}

	private GarminConnectionResponse respond(GarminConnection connection) {
		String ownerId = connection.getOwner().getId();
		return GarminConnectionResponse.from(connection, history.lastRunAt(ownerId).orElse(null),
				history.lastActivity(ownerId).orElse(null));
	}

	@GetMapping("/v1/garmin/connection")
	public GarminConnectionResponse getConnection() {
		String ownerId = AuthContextHolder.get().sub();
		return service.find(ownerId).map(this::respond).orElseGet(GarminConnectionResponse::notConnected);
	}

	@PutMapping("/v1/garmin/connection")
	public GarminConnectionResponse saveConnection(@Valid @RequestBody GarminConnectionRequest request) {
		User owner = userService.getById(AuthContextHolder.get().sub());
		return respond(service.save(owner, request.apiKey(), request.email(), request.keyExpiresOn()));
	}

	@DeleteMapping("/v1/garmin/connection")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteConnection() {
		service.delete(AuthContextHolder.get().sub());
	}
}
