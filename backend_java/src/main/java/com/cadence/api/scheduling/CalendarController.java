package com.cadence.api.scheduling;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.security.AccessGuard;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarController {

	private final SchedulingService schedulingService;
	private final SchedulingMapper schedulingMapper;
	private final AccessGuard accessGuard;

	public CalendarController(SchedulingService schedulingService, SchedulingMapper schedulingMapper, AccessGuard accessGuard) {
		this.schedulingService = schedulingService;
		this.schedulingMapper = schedulingMapper;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/calendar")
	public DataListResponse<ScheduledWorkoutResponse> getCalendar(
			@RequestParam("from") LocalDate from, @RequestParam("to") LocalDate to,
			@RequestParam(name = "athlete_id", required = false) String athleteId) {
		String effectiveAthleteId = athleteId != null ? athleteId : accessGuard.effectiveAthleteId();
		accessGuard.requireRead(effectiveAthleteId);
		var entries = schedulingService.getCalendar(effectiveAthleteId, from, to).stream()
				.map(schedulingMapper::toResponse).toList();
		return new DataListResponse<>(entries);
	}
}
