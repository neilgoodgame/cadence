package com.cadence.api.athletes;

import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** Powers the coach roster's per-athlete compliance % and overdue-workout flag count. */
@Service
public class ComplianceService {

	private static final int WINDOW_DAYS = 28;

	private final ScheduledWorkoutRepository scheduledWorkoutRepository;

	public ComplianceService(ScheduledWorkoutRepository scheduledWorkoutRepository) {
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
	}

	/** Share of scheduled workouts completed as planned, over the trailing window. 0.0 if none were scheduled. */
	public double computeCompliance(String athleteId, LocalDate asOf) {
		LocalDate start = asOf.minusDays(WINDOW_DAYS);
		long total = scheduledWorkoutRepository.countByAthleteIdAndDateBetween(athleteId, start, asOf);
		if (total == 0) {
			return 0.0;
		}
		long completed = scheduledWorkoutRepository.countByAthleteIdAndStatusAndDateBetween(
				athleteId, ScheduledWorkoutStatus.COMPLETED, start, asOf);
		return Math.round((completed / (double) total) * 100) / 100.0;
	}

	/**
	 * Count of scheduled workouts still "planned" after their date has passed. v1: no job ever
	 * flips these to "missed", so overdue-planned is the attention signal, computed at read time.
	 */
	public int computeFlags(String athleteId, LocalDate asOf) {
		LocalDate start = asOf.minusDays(WINDOW_DAYS);
		return (int) scheduledWorkoutRepository.countByAthleteIdAndStatusAndDateGreaterThanEqualAndDateLessThan(
				athleteId, ScheduledWorkoutStatus.PLANNED, start, asOf);
	}
}
