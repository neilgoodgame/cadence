package com.cadence.api.athletes;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Issue #616: GET lists an athlete's threshold exclusions (optionally one field's, or one
 * activity's); POST excludes an activity from one field's threshold detection and rebuilds that
 * field's ledger from the activity's date; DELETE removes an exclusion. {@code preview} returns
 * the same effect without saving anything. Mirrors Django's ThresholdExclusionListView/
 * ThresholdExclusionDetailView. */
@RestController
public class ThresholdExclusionController {

	public record ExcludeRequest(String field, String activityId, Boolean preview) {
	}

	private final ThresholdExclusionService exclusionService;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public ThresholdExclusionController(ThresholdExclusionService exclusionService, UserService userService,
			AccessGuard accessGuard) {
		this.exclusionService = exclusionService;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/athletes/{id}/threshold-exclusions")
	public DataListResponse<ThresholdExclusionService.Exclusion> list(@PathVariable String id,
			@RequestParam(required = false) String field, @RequestParam(name = "activity_id", required = false) String activityId) {
		accessGuard.requireRead(id);
		User athlete = userService.getById(id);
		return new DataListResponse<>(exclusionService.list(
				athlete, field != null ? ThresholdExclusionService.parseField(field) : null, activityId));
	}

	@PostMapping("/v1/athletes/{id}/threshold-exclusions")
	public ThresholdExclusionService.Effect exclude(@PathVariable String id, @RequestBody ExcludeRequest request) {
		String callerSub = accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		if (request.activityId() == null || request.activityId().isBlank()) {
			throw new ValidationException("This field is required.", "activity_id");
		}
		ThresholdField field = ThresholdExclusionService.parseField(request.field());
		User setBy = callerSub.equals(id) ? athlete : userService.getById(callerSub);
		return exclusionService.exclude(athlete, field, request.activityId(), setBy, Boolean.TRUE.equals(request.preview()));
	}

	@DeleteMapping("/v1/athletes/{id}/threshold-exclusions/{field}/{activityId}")
	public ThresholdExclusionService.Effect include(@PathVariable String id, @PathVariable String field,
			@PathVariable String activityId, @RequestParam(defaultValue = "false") boolean preview) {
		accessGuard.requireWrite(id);
		User athlete = userService.getById(id);
		return exclusionService.include(athlete, ThresholdExclusionService.parseField(field), activityId, preview);
	}
}
