package com.cadence.api.scheduling;

import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.users.User;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

/**
 * Manual mapping (not MapStruct): every association here is only ever read via {@code .getId()},
 * the one access pattern that's always safe regardless of session/transaction state - except
 * {@code assignedBy.getName()}/{@code .isVirtual()}, gated behind {@link Hibernate#isInitialized}
 * instead. This method is shared by call sites with very different loading paths (the calendar
 * list, exports, webhooks, and every plain {@code findById} all leave {@code assignedBy} as an
 * uninitialized lazy proxy; the scheduled-workout detail endpoint and the create path load it for
 * real - see {@code ScheduledWorkoutRepository.findByIdWithAssignedBy} and
 * {@code SchedulingService.schedule}) - accessing anything beyond {@code .getId()} on the
 * uninitialized ones would throw {@code LazyInitializationException} once the loading
 * transaction's session has closed, so this checks first and degrades to null/false rather than
 * forcing every caller to join-fetch an association only the detail view actually needs.
 */
@Component
public class SchedulingMapper {

	public ScheduledWorkoutResponse toResponse(ScheduledWorkout s) {
		User assignedBy = s.getAssignedBy();
		boolean assignedByLoaded = assignedBy != null && Hibernate.isInitialized(assignedBy);
		return new ScheduledWorkoutResponse(
				s.getId(),
				s.getWorkout().getId(),
				s.getAthlete().getId(),
				assignedBy != null ? assignedBy.getId() : null,
				assignedByLoaded ? assignedBy.getName() : null,
				assignedByLoaded && assignedBy.isVirtual(),
				s.getDate(),
				s.getTimeOfDay(),
				s.getStatus(),
				s.getActivity() != null ? s.getActivity().getId() : null,
				s.getNotes());
	}
}
