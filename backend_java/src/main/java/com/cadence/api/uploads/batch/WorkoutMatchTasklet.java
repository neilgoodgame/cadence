package com.cadence.api.uploads.batch;

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
import java.util.List;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Links a same-day, same-sport, still-planned {@link ScheduledWorkout} to the newly-ingested activity, if one exists. */
@Component
@StepScope
public class WorkoutMatchTasklet implements Tasklet {

	private final UploadJobContext context;
	private final ActivityRepository activityRepository;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;
	private final ApplicationEventPublisher eventPublisher;

	public WorkoutMatchTasklet(UploadJobContext context, ActivityRepository activityRepository,
			ScheduledWorkoutRepository scheduledWorkoutRepository, TagRepository tagRepository,
			ActivityTagRepository activityTagRepository, ApplicationEventPublisher eventPublisher) {
		this.context = context;
		this.activityRepository = activityRepository;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		// Matching is per-sport, so a multisport parent never matches (no designed workout has
		// sport 'multisport') but its bike/run legs can each complete their own scheduled workout.
		for (UploadJobContext.Segment segment : context.getSegments()) {
			matchSegment(segment.activityId());
		}
		return RepeatStatus.FINISHED;
	}

	private void matchSegment(String activityId) {
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

		activity.setWorkout(candidate.getWorkout());
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
		eventPublisher.publishEvent(new ScheduledWorkoutMatchedEvent(candidate.getId(), athlete.getId()));
	}
}
