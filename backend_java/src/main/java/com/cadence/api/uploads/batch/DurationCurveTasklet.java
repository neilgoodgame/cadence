package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.DurationCurveComputeService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.List;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DurationCurveTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final DurationCurveComputeService computeService;

	public DurationCurveTasklet(UploadJobContextRegistry contextRegistry, ActivityRepository activityRepository,
			DurationCurveComputeService computeService) {
		this.contextRegistry = contextRegistry;
		this.activityRepository = activityRepository;
		this.computeService = computeService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			// A multisport parent's stream mixes sports, so a curve over it compares
			// incomparable efforts - each leg gets its own instead.
			if (activity.getSport() == Sport.MULTISPORT) {
				continue;
			}

			List<Integer> powerSeries = segment.parsed().samples().stream().map(ParsedActivity.Sample::power).toList();
			List<Integer> hrSeries = segment.parsed().samples().stream().map(ParsedActivity.Sample::heartrate).toList();
			computeService.computeForActivity(activity, powerSeries, hrSeries);
		}
		return RepeatStatus.FINISHED;
	}
}
