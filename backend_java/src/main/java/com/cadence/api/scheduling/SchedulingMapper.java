package com.cadence.api.scheduling;

import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import org.springframework.stereotype.Component;

/** Manual mapping (not MapStruct): every association here is only ever read via {@code .getId()}, the one access pattern that's always safe regardless of session/transaction state. */
@Component
public class SchedulingMapper {

	public ScheduledWorkoutResponse toResponse(ScheduledWorkout s) {
		return new ScheduledWorkoutResponse(
				s.getId(),
				s.getWorkout().getId(),
				s.getAthlete().getId(),
				s.getAssignedBy() != null ? s.getAssignedBy().getId() : null,
				s.getDate(),
				s.getTimeOfDay(),
				s.getStatus(),
				s.getActivity() != null ? s.getActivity().getId() : null);
	}
}
