package com.cadence.api.activities;

import com.cadence.api.activities.dto.StreamsResponse;
import com.cadence.api.security.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreamController {

	private final ActivityService activityService;
	private final StreamService streamService;
	private final AccessGuard accessGuard;

	public StreamController(ActivityService activityService, StreamService streamService, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.streamService = streamService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/streams")
	public StreamsResponse getStreams(@PathVariable String id,
			@RequestParam(required = false) String fields,
			@RequestParam(defaultValue = "high") String resolution) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		return streamService.getStreams(activity, fields, resolution);
	}
}
