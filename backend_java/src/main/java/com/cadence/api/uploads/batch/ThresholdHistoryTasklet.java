package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.common.error.NotFoundException;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs right after records are loaded and before {@link ComputeDerivedStatsTasklet} - so if this
 * activity's own effort raises (or, once an old best-effort ages out, lowers) the athlete's
 * current window value, this same activity's own TSS/intensity is rated against the new value
 * too, not just future ones. See ThresholdHistoryService.recomputeForActivity.
 */
@Component
public class ThresholdHistoryTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final ThresholdHistoryService thresholdHistoryService;

	public ThresholdHistoryTasklet(UploadJobContextRegistry contextRegistry, ActivityRepository activityRepository,
			ThresholdHistoryService thresholdHistoryService) {
		this.contextRegistry = contextRegistry;
		this.activityRepository = activityRepository;
		this.thresholdHistoryService = thresholdHistoryService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			thresholdHistoryService.recomputeForActivity(activity);
		}
		return RepeatStatus.FINISHED;
	}
}
