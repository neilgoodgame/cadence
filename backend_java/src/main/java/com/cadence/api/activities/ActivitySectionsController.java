package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivitySectionsResponse;
import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.activities.dto.CustomSplitsRequest;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.courses.CourseService;
import com.cadence.api.security.AccessGuard;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivitySectionsController {

	private final ActivityService activityService;
	private final ActivitySectionsService sectionsService;
	private final ActivitySplitsService splitsService;
	private final CourseService courseService;
	private final AccessGuard accessGuard;

	public ActivitySectionsController(ActivityService activityService, ActivitySectionsService sectionsService,
			ActivitySplitsService splitsService, CourseService courseService, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.sectionsService = sectionsService;
		this.splitsService = splitsService;
		this.courseService = courseService;
		this.accessGuard = accessGuard;
	}

	/** Laps, fixed 1km/5km splits, or (elevation) one split per section of the activity's own
	 * recorded elevation - no race reference/delta, unlike GET /v1/races/{id}/splits. */
	@GetMapping("/v1/activities/{id}/splits")
	public ActivitySplitsResponse getSplits(@PathVariable String id, @RequestParam(defaultValue = "laps") String mode) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		if (!ActivitySplitsService.SPLITS_MODES.contains(mode)) {
			throw new ValidationException("Must be one of laps, 1, 5, elevation.", "mode");
		}
		return splitsService.getSplits(activity, mode);
	}

	/** Custom splits from a client-supplied list of steps - see
	 * ActivitySplitsService.customSplits. */
	@PostMapping("/v1/activities/{id}/splits")
	public ActivitySplitsResponse postCustomSplits(@PathVariable String id, @RequestBody CustomSplitsRequest request) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return splitsService.customSplits(activity, request.steps());
	}

	/** Flat / climb / descent sections of a run, walk or ride, calculated on each read with the
	 * owner's course section settings - or a preview's overrides from the query string. */
	@GetMapping("/v1/activities/{id}/sections")
	public ActivitySectionsResponse getSections(@PathVariable String id, @RequestParam Map<String, String> query) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		sectionsService.requireSectionSport(activity);
		CourseSections.Settings settings = courseService.settingsWithOverrides(activity.getAthlete().getId(), query);
		return sectionsService.toResponse(activity, sectionsService.ensureElevation(activity), settings);
	}

	/** Rebuilds the stored elevation series from the activity's records. */
	@PostMapping("/v1/activities/{id}/recompute-elevation")
	public ActivitySectionsResponse recomputeElevation(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		sectionsService.requireSectionSport(activity);
		return sectionsService.toResponse(activity, sectionsService.rebuildElevation(activity), null);
	}
}
