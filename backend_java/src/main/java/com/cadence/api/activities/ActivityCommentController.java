package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivityCommentCreateRequest;
import com.cadence.api.activities.dto.ActivityCommentResponse;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityCommentController {

	private final ActivityCommentService activityCommentService;
	private final ActivityService activityService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public ActivityCommentController(ActivityCommentService activityCommentService, ActivityService activityService,
			UserService userService, AccessGuard accessGuard) {
		this.activityCommentService = activityCommentService;
		this.activityService = activityService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/comments")
	public DataListResponse<ActivityCommentResponse> listComments(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return new DataListResponse<>(activityCommentService.list(id));
	}

	@PostMapping("/v1/activities/{id}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public ActivityCommentResponse createComment(@PathVariable String id,
			@Valid @RequestBody ActivityCommentCreateRequest request) {
		Activity activity = activityService.getActivity(id);
		String sub = accessGuard.requireRead(activity.getAthlete().getId());
		User author = userService.getById(sub);
		return activityCommentService.create(activity, author, request.text(), request.parentId());
	}

	@DeleteMapping("/v1/activities/{id}/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComment(@PathVariable String id, @PathVariable String commentId) {
		Activity activity = activityService.getActivity(id);
		String sub = accessGuard.requireRead(activity.getAthlete().getId());
		activityCommentService.delete(id, commentId, sub);
	}
}
