package com.cadence.api.activities;

import com.cadence.api.activities.dto.DurationCurveResponse;
import com.cadence.api.security.AccessGuard;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurveController {

	private final ActivityService activityService;
	private final DurationCurveRepository durationCurveRepository;
	private final AccessGuard accessGuard;

	public CurveController(ActivityService activityService, DurationCurveRepository durationCurveRepository, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.durationCurveRepository = durationCurveRepository;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/curves")
	public DurationCurveResponse getCurves(@PathVariable String id,
			@RequestParam(defaultValue = "power") DurationCurveMetric metric) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return durationCurveRepository.findByActivityIdAndMetric(id, metric)
				.map(dc -> new DurationCurveResponse(dc.getMetric(), dc.getExtendsTo(), dc.getPoints()))
				.orElse(new DurationCurveResponse(metric, 0, Map.of()));
	}
}
