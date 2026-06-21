package com.cadence.api.activities;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LapController {

	private final ActivityService activityService;
	private final LapRepository lapRepository;
	private final LapMapper lapMapper;
	private final AccessGuard accessGuard;

	public LapController(ActivityService activityService, LapRepository lapRepository, LapMapper lapMapper, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.lapRepository = lapRepository;
		this.lapMapper = lapMapper;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/laps")
	public DataListResponse<com.cadence.api.activities.dto.LapResponse> listLaps(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		var laps = lapRepository.findByActivityIdOrderByIndex(id).stream().map(lapMapper::toResponse).toList();
		return new DataListResponse<>(laps);
	}
}
