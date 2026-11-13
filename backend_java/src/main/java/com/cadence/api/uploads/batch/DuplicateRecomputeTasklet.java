package com.cadence.api.uploads.batch;

import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryKind;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Final step of {@code processUploadJob}: after the upload is already READY, rebuilds the
 * threshold ledger (and re-rates TSS) if ingest-time overlap handling marked a duplicate.
 *
 * <p>This used to run inline in {@link ParseFileTasklet}'s transaction, rebuilding every field
 * across the athlete's whole history before the upload even left "queued". Now it (a) runs after
 * the upload is READY, and (b) is scoped: only fields the sport can define (bike: FTP; run:
 * critical run power and threshold pace), and only those where the demoted duplicate is actually
 * a measured ledger source. A duplicate that was never a source can't change any window's winner
 * (the new activity, if it's the demoted side, was already excluded from the ledger by the
 * duplicate filter before {@code thresholdHistoryStep} ran), so nothing needs rebuilding.
 *
 * <p>Failures are logged, not propagated: the upload has already succeeded and a failed job here
 * would make {@link UploadJobExecutionListener} flip it to FAILED. The rebuild itself is the
 * from-date variant (issue #545) - it replays only from the demoted activity's date onward, not
 * the athlete's whole history.
 */
@Component
public class DuplicateRecomputeTasklet implements Tasklet {

	private static final Logger log = LoggerFactory.getLogger(DuplicateRecomputeTasklet.class);

	private final UploadJobContextRegistry contextRegistry;
	private final UploadRepository uploadRepository;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final ActivityService activityService;
	private final ActivityRepository activityRepository;

	public DuplicateRecomputeTasklet(UploadJobContextRegistry contextRegistry, UploadRepository uploadRepository,
			ThresholdHistoryRepository thresholdHistoryRepository, ActivityService activityService,
			ActivityRepository activityRepository) {
		this.contextRegistry = contextRegistry;
		this.uploadRepository = uploadRepository;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.activityService = activityService;
		this.activityRepository = activityRepository;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext.DuplicateChange change = contextRegistry.forUpload(uploadId).getDuplicateChange();
		if (change == null) {
			return RepeatStatus.FINISHED;
		}
		try {
			Upload upload = uploadRepository.findById(uploadId).orElse(null);
			if (upload == null) {
				return RepeatStatus.FINISHED;
			}
			Set<ThresholdField> fields = affectedFields(change);
			// Issue #545: the recompute can start at the demoted activity's own date - its
			// duplicate status is the only thing that changed. A missing activity (deleted in the
			// meantime) falls back to the full rebuild via a null date.
			LocalDate fromDate = activityRepository.findById(change.demotedActivityId())
					.map(a -> a.getStartDate().atZone(ZoneOffset.UTC).toLocalDate())
					.orElse(null);
			activityService.recomputeAfterDuplicateChange(upload.getAthlete(), fields, fromDate);
		}
		catch (RuntimeException e) {
			log.error("Post-upload duplicate recompute failed for upload {} (upload itself is unaffected)", uploadId, e);
		}
		return RepeatStatus.FINISHED;
	}

	Set<ThresholdField> affectedFields(UploadJobContext.DuplicateChange change) {
		Set<ThresholdField> candidates = ThresholdField.forSport(change.sport());
		Set<ThresholdField> affected = EnumSet.noneOf(ThresholdField.class);
		if (candidates.isEmpty()) {
			return affected;
		}
		for (ThresholdHistory row : thresholdHistoryRepository.findBySourceActivityId(change.demotedActivityId())) {
			if (row.getKind() == ThresholdHistoryKind.MEASURED && candidates.contains(row.getField())) {
				affected.add(row.getField());
			}
		}
		return affected;
	}
}
