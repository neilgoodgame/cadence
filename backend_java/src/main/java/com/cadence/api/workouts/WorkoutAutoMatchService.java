package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.users.User;
import com.cadence.api.webhooks.ScheduledWorkoutMatchedEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links a same-day, same-sport, still-planned {@link ScheduledWorkout} to a newly-ingested
 * activity, if one exists - the actual matching logic behind {@code WorkoutMatchTasklet},
 * extracted into a plain injectable service (rather than living directly in the Spring Batch
 * tasklet) so it's directly unit-testable. Not to be confused with {@link WorkoutMatchService},
 * which is read-only (backs {@code GET /v1/workouts/{id}/matches}) and doesn't perform matching.
 */
@Service
public class WorkoutAutoMatchService {

	// Matches ParseFileTasklet.DATE_FORMAT - the same date format the default
	// "{sport} on {date}" activity name already uses.
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final ActivityRepository activityRepository;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;
	private final ApplicationEventPublisher eventPublisher;

	public WorkoutAutoMatchService(ActivityRepository activityRepository,
			ScheduledWorkoutRepository scheduledWorkoutRepository, TagRepository tagRepository,
			ActivityTagRepository activityTagRepository, ApplicationEventPublisher eventPublisher) {
		this.activityRepository = activityRepository;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public void attemptMatch(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		User athlete = activity.getAthlete();
		LocalDate date = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();

		List<ScheduledWorkout> candidates = scheduledWorkoutRepository.findMatchCandidates(athlete.getId(), date, activity.getSport());
		if (candidates.isEmpty()) {
			return;
		}
		ScheduledWorkout candidate = candidates.get(0);
		candidate.setActivity(activity);
		candidate.setStatus(ScheduledWorkoutStatus.COMPLETED);
		scheduledWorkoutRepository.save(candidate);

		Workout workout = candidate.getWorkout();
		activity.setWorkout(workout);
		if (athlete.isRenameMatchedActivities()) {
			activity.setName(matchedActivityName(workout, activity, athlete));
		}
		activityRepository.save(activity);

		Tag tag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Auto-matched").orElseGet(() -> {
			Tag created = new Tag();
			created.setAthlete(athlete);
			created.setName("Auto-matched");
			created.setOrigin(TagOrigin.AUTO);
			return tagRepository.save(created);
		});
		if (!activityTagRepository.existsByActivityIdAndTagId(activity.getId(), tag.getId())) {
			ActivityTag link = new ActivityTag();
			link.setActivity(activity);
			link.setTag(tag);
			activityTagRepository.save(link);
		}
		if (athlete.isCopyMatchedWorkoutTags()) {
			for (String workoutTagName : workout.getTags()) {
				if (workoutTagName.isBlank()) {
					continue;
				}
				Tag workoutTag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), workoutTagName)
						.orElseGet(() -> {
							Tag created = new Tag();
							created.setAthlete(athlete);
							created.setName(workoutTagName);
							created.setOrigin(TagOrigin.AUTO);
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
		eventPublisher.publishEvent(new ScheduledWorkoutMatchedEvent(candidate.getId(), athlete.getId()));
	}

	/** athlete.isRenameMatchedActivities()'s naming. */
	private String matchedActivityName(Workout workout, Activity activity, User athlete) {
		if (athlete.isAppendMatchDateToName()) {
			return workout.getName() + " - " + DATE_FORMAT.format(activity.getStartDate().atZone(ZoneOffset.UTC));
		}
		return workout.getName();
	}
}
