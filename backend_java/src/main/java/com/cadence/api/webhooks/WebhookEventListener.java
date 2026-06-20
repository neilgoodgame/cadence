package com.cadence.api.webhooks;

import com.cadence.api.activities.ActivityService;
import com.cadence.api.scheduling.SchedulingMapper;
import com.cadence.api.scheduling.SchedulingService;
import com.cadence.api.uploads.UploadMapper;
import com.cadence.api.uploads.UploadService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the lightweight domain events the upload/scheduling pipelines publish into actual
 * webhook deliveries - deferred to {@code AFTER_COMMIT} so a delivery is never enqueued (and a
 * worker never races to read) a row whose transaction hasn't committed yet, the same hazard
 * {@code UploadIngestService} calls out for not launching its batch job inside its own
 * transaction. Keeps the firing call sites themselves (the upload/scheduling tasklets) free of
 * any webhook-specific concern - they just publish a plain record.
 */
@Component
public class WebhookEventListener {

	private final WebhookEventPublisher webhookEventPublisher;
	private final ActivityService activityService;
	private final SchedulingService schedulingService;
	private final SchedulingMapper schedulingMapper;
	private final UploadService uploadService;
	private final UploadMapper uploadMapper;

	public WebhookEventListener(WebhookEventPublisher webhookEventPublisher, ActivityService activityService,
			SchedulingService schedulingService, SchedulingMapper schedulingMapper, UploadService uploadService,
			UploadMapper uploadMapper) {
		this.webhookEventPublisher = webhookEventPublisher;
		this.activityService = activityService;
		this.schedulingService = schedulingService;
		this.schedulingMapper = schedulingMapper;
		this.uploadService = uploadService;
		this.uploadMapper = uploadMapper;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onActivityCreated(ActivityCreatedEvent event) {
		var activity = activityService.getActivity(event.activityId());
		webhookEventPublisher.fireEvent(WebhookEvent.ACTIVITY_CREATED, event.athleteId(), activityService.toResponse(activity));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onScheduledWorkoutMatched(ScheduledWorkoutMatchedEvent event) {
		var scheduled = schedulingService.getScheduledWorkout(event.scheduledWorkoutId());
		webhookEventPublisher.fireEvent(
				WebhookEvent.SCHEDULED_WORKOUT_MATCHED, event.athleteId(), schedulingMapper.toResponse(scheduled));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onUploadBatchCompleted(UploadBatchCompletedEvent event) {
		var batch = uploadService.getUploadBatch(event.batchId());
		var children = uploadService.getBatchChildren(event.batchId());
		webhookEventPublisher.fireEvent(WebhookEvent.UPLOAD_BATCH_COMPLETED, event.athleteId(), uploadMapper.toResponse(batch, children));
	}
}
