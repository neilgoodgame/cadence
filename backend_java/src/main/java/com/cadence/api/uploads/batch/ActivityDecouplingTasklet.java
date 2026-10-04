package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityDecouplingService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aerobic decoupling and durability - runs right after {@link ThresholdHistoryTasklet} (IF
 * needs the FTP/CP threshold at this activity's own date, which this same activity's effort may
 * have just set) and before {@link ComputeDerivedStatsTasklet}. See ActivityDecouplingService.
 */
@Component
public class ActivityDecouplingTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final ActivityDecouplingService decouplingService;

	public ActivityDecouplingTasklet(UploadJobContextRegistry contextRegistry, ActivityRepository activityRepository,
			ActivityDecouplingService decouplingService) {
		this.contextRegistry = contextRegistry;
		this.activityRepository = activityRepository;
		this.decouplingService = decouplingService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			if (activity.getSport() == Sport.BIKE || activity.getSport() == Sport.RUN) {
				decouplingService.computeAndPersist(activity, activity.getAthlete());
			}
		}
		return RepeatStatus.FINISHED;
	}
}
