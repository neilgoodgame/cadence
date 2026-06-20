package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.webhooks.ActivityCreatedEvent;
import java.time.Instant;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@StepScope
public class FinalizeUploadTasklet implements Tasklet {

	private final UploadJobContext context;
	private final UploadRepository uploadRepository;
	private final ActivityRepository activityRepository;
	private final ApplicationEventPublisher eventPublisher;

	public FinalizeUploadTasklet(UploadJobContext context, UploadRepository uploadRepository,
			ActivityRepository activityRepository, ApplicationEventPublisher eventPublisher) {
		this.context = context;
		this.uploadRepository = uploadRepository;
		this.activityRepository = activityRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		Upload upload = uploadRepository.findById(context.getUploadId())
				.orElseThrow(() -> new NotFoundException("No such upload."));
		Activity activity = activityRepository.findById(context.getActivityId())
				.orElseThrow(() -> new NotFoundException("No such activity."));
		upload.setStatus(UploadStatus.READY);
		upload.setActivity(activity);
		upload.setProgress(1.0);
		upload.setCompletedAt(Instant.now());
		uploadRepository.save(upload);
		eventPublisher.publishEvent(new ActivityCreatedEvent(activity.getId(), upload.getAthlete().getId()));
		return RepeatStatus.FINISHED;
	}
}
