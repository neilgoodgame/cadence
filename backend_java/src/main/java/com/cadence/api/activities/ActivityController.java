package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.security.AccessGuard;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {

	private final ActivityService activityService;
	private final AccessGuard accessGuard;

	public ActivityController(ActivityService activityService, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities")
	public CursorPage<ActivityResponse> listActivities(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Sport sport,
			@RequestParam(required = false) Environment environment,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String cursor) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		int effectiveLimit = Math.max(1, Math.min(limit, 200));
		String effectiveQuery = mergeSortIntoQuery(q, sort);
		return activityService.list(athleteId, effectiveQuery, sport, environment, cursor, effectiveLimit);
	}

	@GetMapping("/v1/activities/{id}")
	public ActivityResponse getActivity(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return activityService.toResponse(activity);
	}

	@PatchMapping("/v1/activities/{id}")
	public ActivityResponse updateActivity(@PathVariable String id, @RequestBody Map<String, Object> body) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.updateActivity(activity, body);
		return activityService.toResponse(updated);
	}

	@DeleteMapping("/v1/activities/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteActivity(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		activityService.deleteActivity(id);
	}

	/** {@code sort} (a separate query param, e.g. {@code -tss}) only applies when the CQL query has no {@code orderby} of its own. */
	private String mergeSortIntoQuery(String q, String sort) {
		if (sort == null || sort.isBlank()) {
			return q;
		}
		String direction = sort.startsWith("-") ? "desc" : "asc";
		String field = sort.startsWith("-") ? sort.substring(1) : sort;
		String sortClause = "orderby " + field + " " + direction;
		if (q == null || q.isBlank()) {
			return sortClause;
		}
		return q.toLowerCase().contains("orderby") ? q : q + " " + sortClause;
	}
}
