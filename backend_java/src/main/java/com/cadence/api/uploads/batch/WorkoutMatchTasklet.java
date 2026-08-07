package com.cadence.api.uploads.batch;

import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.workouts.WorkoutAutoMatchService;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Spring Batch adapter for {@link WorkoutAutoMatchService} - links a same-day, same-sport,
 * still-planned {@link ScheduledWorkout} to each newly-ingested activity, if one exists. */
@Component
public class WorkoutMatchTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final WorkoutAutoMatchService autoMatchService;

	public WorkoutMatchTasklet(UploadJobContextRegistry contextRegistry, WorkoutAutoMatchService autoMatchService) {
		this.contextRegistry = contextRegistry;
		this.autoMatchService = autoMatchService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
		// Matching is per-sport, so a multisport parent never matches (no designed workout has
		// sport 'multisport') but its bike/run legs can each complete their own scheduled workout.
		for (UploadJobContext.Segment segment : context.getSegments()) {
			autoMatchService.attemptMatch(segment.activityId());
		}
		return RepeatStatus.FINISHED;
	}
}
