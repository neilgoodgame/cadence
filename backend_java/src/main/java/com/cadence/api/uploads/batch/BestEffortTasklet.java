package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.BestEffortComputeService;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BestEffortTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final BestEffortComputeService computeService;

	public BestEffortTasklet(UploadJobContextRegistry contextRegistry, ActivityRepository activityRepository,
			BestEffortComputeService computeService) {
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
			User athlete = activity.getAthlete();
			List<Integer> powerSeries = segment.parsed().samples().stream()
					.map(ParsedActivity.Sample::power).toList();
			List<Integer> hrSeries = segment.parsed().samples().stream()
					.map(ParsedActivity.Sample::heartrate).toList();
			List<Double> distanceSeries = segment.parsed().samples().stream()
					.map(ParsedActivity.Sample::distanceKm).toList();
			List<Integer> tSeries = segment.parsed().samples().stream()
					.map(ParsedActivity.Sample::t).toList();
			computeService.computeForActivity(activity, athlete, powerSeries, hrSeries, tSeries, distanceSeries);
		}
		return RepeatStatus.FINISHED;
	}
}
