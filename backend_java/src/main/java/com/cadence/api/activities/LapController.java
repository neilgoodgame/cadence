package com.cadence.api.activities;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.workouts.Workout;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LapController {

	private final ActivityService activityService;
	private final LapRepository lapRepository;
	private final LapMapper lapMapper;
	private final LapDerivationService lapDerivationService;
	private final AccessGuard accessGuard;

	public LapController(ActivityService activityService, LapRepository lapRepository, LapMapper lapMapper,
			LapDerivationService lapDerivationService, AccessGuard accessGuard) {
		this.activityService = activityService;
		this.lapRepository = lapRepository;
		this.lapMapper = lapMapper;
		this.lapDerivationService = lapDerivationService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/activities/{id}/laps")
	public DataListResponse<com.cadence.api.activities.dto.LapResponse> listLaps(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireRead(activity.getAthlete().getId());
		var laps = lapRepository.findByActivityIdOrderByIndexFetchWorkoutStep(id).stream().map(lapMapper::toResponse).toList();
		return new DataListResponse<>(laps);
	}

	/**
	 * Manual, on-demand equivalent of {@code WorkoutAutoMatchService.attemptMatch}'s
	 * {@code LapSource.MATCHED_WORKOUT} branch - always derives from the matched workout
	 * regardless of the athlete's current {@code lapSource} preference (that preference only
	 * governs a *new* import's default), so an already-matched activity (auto-matched before
	 * this feature existed, or linked manually via {@code ActivityService.updateActivity}'s
	 * {@code workout_id} handling, which never touches laps) can opt in on demand.
	 */
	@PostMapping("/v1/activities/{id}/regenerate-laps")
	public DataListResponse<com.cadence.api.activities.dto.LapResponse> regenerateLaps(@PathVariable String id) {
		Activity activity = activityService.getActivity(id);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Workout workout = activity.getWorkout();
		if (workout == null) {
			throw new ValidationException("This activity isn't linked to a workout.", "workout_id");
		}
		if (!lapDerivationService.replaceLapsWithDerived(activity, workout)) {
			throw new ValidationException(
					"Laps can't be derived from this workout (a manual-ended step, or no recorded data).", "workout_id");
		}
		var laps = lapRepository.findByActivityIdOrderByIndexFetchWorkoutStep(id).stream().map(lapMapper::toResponse).toList();
		return new DataListResponse<>(laps);
	}
}
