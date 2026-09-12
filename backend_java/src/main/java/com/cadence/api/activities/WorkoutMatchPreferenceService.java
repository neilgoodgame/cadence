package com.cadence.api.activities;

import com.cadence.api.athletes.LapSource;
import com.cadence.api.users.User;
import com.cadence.api.workouts.Workout;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * What happens to an activity when it gets linked to a workout template - shared between
 * ingest-time auto-match ({@code WorkoutAutoMatchService.attemptMatch}, matched by date against a
 * {@code ScheduledWorkout}) and manually accepting a Scan-for-matches candidate
 * ({@code ActivityService.updateActivity}). Both are "this activity is now matched to that
 * workout" in the same sense, so they apply the same athlete preferences rather than each having
 * their own.
 *
 * <p>Deliberately excludes the "Auto-matched" system tag {@code WorkoutAutoMatchService} also
 * applies - that tag specifically marks a match the system made without a human confirming it,
 * which is the opposite of what accepting a candidate means.
 */
@Service
public class WorkoutMatchPreferenceService {

	// Matches WorkoutAutoMatchService's own DATE_FORMAT - the same date format the default
	// "{sport} on {date}" activity name already uses.
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;
	private final LapDerivationService lapDerivationService;

	public WorkoutMatchPreferenceService(TagRepository tagRepository, ActivityTagRepository activityTagRepository,
			LapDerivationService lapDerivationService) {
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
		this.lapDerivationService = lapDerivationService;
	}

	/** athlete.isRenameMatchedActivities()'s naming. */
	public String matchedActivityName(Workout workout, Activity activity, User athlete) {
		if (athlete.isAppendMatchDateToName()) {
			return workout.getName() + " - " + DATE_FORMAT.format(activity.getStartDate().atZone(ZoneOffset.UTC));
		}
		return workout.getName();
	}

	/** Mutates activity's name per athlete.isRenameMatchedActivities() - caller still owns the
	 * actual save. Skipped when explicitNameSet is true (an explicit rename in the same request
	 * wins over the preference-driven one). */
	public void applyRename(Activity activity, Workout workout, User athlete, boolean explicitNameSet) {
		if (athlete.isRenameMatchedActivities() && !explicitNameSet) {
			activity.setName(matchedActivityName(workout, activity, athlete));
		}
	}

	/** Copies the workout's tags (if enabled) and (re)derives laps from it (if enabled) - call
	 * once activity.workout has actually been saved, since lap derivation reads it back. */
	public void applySideEffects(Activity activity, Workout workout, User athlete) {
		if (athlete.isCopyMatchedWorkoutTags()) {
			for (String workoutTagName : workout.getTags()) {
				if (workoutTagName.isBlank()) {
					continue;
				}
				// MANUAL, not AUTO: these are the workout's own descriptive tags (e.g. "road",
				// "marathon") - ordinary content that happens to be copied automatically, not a
				// system marker like "Auto-matched". AUTO would permanently block removal (see
				// TagService.detachTag) on every activity that ever reuses this tag name.
				Tag workoutTag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), workoutTagName)
						.orElseGet(() -> {
							Tag created = new Tag();
							created.setAthlete(athlete);
							created.setName(workoutTagName);
							created.setOrigin(TagOrigin.MANUAL);
							return tagRepository.save(created);
						});
				if (!activityTagRepository.existsByActivityIdAndTagId(activity.getId(), workoutTag.getId())) {
					ActivityTag link = new ActivityTag();
					link.setActivity(activity);
					link.setTag(workoutTag);
					activityTagRepository.save(link);
				}
			}
		}
		if (athlete.getLapSource() == LapSource.MATCHED_WORKOUT) {
			lapDerivationService.replaceLapsWithDerived(activity, workout);
		}
	}
}
