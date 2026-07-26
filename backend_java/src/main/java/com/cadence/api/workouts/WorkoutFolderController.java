package com.cadence.api.workouts;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.workouts.dto.WorkoutFolderRequest;
import com.cadence.api.workouts.dto.WorkoutFolderResponse;
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
public class WorkoutFolderController {

	private final WorkoutFolderService workoutFolderService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public WorkoutFolderController(WorkoutFolderService workoutFolderService, UserService userService, AccessGuard accessGuard) {
		this.workoutFolderService = workoutFolderService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/workout-folders")
	public DataListResponse<WorkoutFolderResponse> listFolders() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(workoutFolderService.listFolders(athleteId));
	}

	@PostMapping("/v1/workout-folders")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutFolderResponse createFolder(@RequestBody WorkoutFolderRequest request) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User creator = userService.getById(athleteId);
		return workoutFolderService.createFolder(creator, request.name());
	}

	@PatchMapping("/v1/workout-folders/{id}")
	public WorkoutFolderResponse renameFolder(@PathVariable String id, @RequestBody WorkoutFolderRequest request) {
		WorkoutFolder folder = workoutFolderService.getFolder(id);
		accessGuard.requireWrite(folder.getCreatedBy().getId());
		return workoutFolderService.renameFolder(folder, request.name());
	}

	@DeleteMapping("/v1/workout-folders/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFolder(@PathVariable String id) {
		WorkoutFolder folder = workoutFolderService.getFolder(id);
		accessGuard.requireWrite(folder.getCreatedBy().getId());
		workoutFolderService.deleteFolder(folder);
	}
}
