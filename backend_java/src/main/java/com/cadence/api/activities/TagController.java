package com.cadence.api.activities;

import com.cadence.api.activities.dto.TagAttachRequest;
import com.cadence.api.activities.dto.TagAttachResponse;
import com.cadence.api.activities.dto.TagResponse;
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
public class TagController {

	private final TagService tagService;
	private final TagMapper tagMapper;
	private final ActivityService activityService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public TagController(TagService tagService, TagMapper tagMapper, ActivityService activityService,
			UserService userService, AccessGuard accessGuard) {
		this.tagService = tagService;
		this.tagMapper = tagMapper;
		this.activityService = activityService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/tags")
	public DataListResponse<TagResponse> listTags() {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return new DataListResponse<>(tagService.listTags(athleteId).stream().map(tagMapper::toResponse).toList());
	}

	@PostMapping("/v1/activities/{id}/tags")
	@ResponseStatus(HttpStatus.CREATED)
	public TagAttachResponse tagActivity(@PathVariable String id, @Valid @RequestBody TagAttachRequest request) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		User athlete = userService.getById(activity.getAthlete().getId());
		Tag tag = tagService.attachTag(activity, athlete, request.tagId(), request.name());
		return new TagAttachResponse(id, tagMapper.toResponse(tag));
	}

	@DeleteMapping("/v1/activities/{id}/tags/{tagId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void untagActivity(@PathVariable String id, @PathVariable String tagId) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		tagService.detachTag(id, tagId);
	}
}
