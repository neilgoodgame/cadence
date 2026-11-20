package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityPostProcessingService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.calc.TrainingEffectLabel;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #525: replaces ThresholdHistoryTasklet, ActivityDecouplingTasklet,
 * ComputeDerivedStatsTasklet, DurationCurveTasklet, BestEffortTasklet, and WorkoutMatchTasklet -
 * all six delegated into one real implementation ({@link ActivityPostProcessingService
 * #processNewActivity}) instead of duplicating its ordering/logic as six separate ingest-only
 * steps. {@code avgLeftBalancePct} (and the device-reported training-effect fields) are the only
 * things genuinely parse-time-only - see {@code
 * ActivityPostProcessingService#recomputePowerHrAndDecoupling}'s own Javadoc - computed here
 * directly from the still-in-memory {@link ParsedActivity} and saved once; everything else comes
 * from the Record rows {@code loadRecordsStep} already committed, same data source every other
 * recompute path (merge, backfill, admin recompute) already uses.
 *
 * <p>Called unconditionally for every segment, multisport parent included - unlike the six
 * tasklets this replaces, no {@code isMultisportParent} branch is needed:
 * {@code recomputeDurationCurveAndBestEfforts} already excludes multisport/transition sport
 * internally; decoupling (folded into {@code recomputePowerHrAndDecoupling}) already excludes
 * anything but bike/run; workout-match and the threshold check are both provable no-ops for
 * sport {@code MULTISPORT} (no {@code ScheduledWorkout} or threshold field is ever defined for
 * it - {@code WorkoutMatchTasklet}'s own Javadoc already said as much); and this tasklet still
 * overwrites the parent's own {@code tss} as the sum of its legs' right after, same as {@code
 * ComputeDerivedStatsTasklet} did.
 */
@Component
public class PostProcessNewActivityTasklet implements Tasklet {

	private final UploadJobContextRegistry contextRegistry;
	private final ActivityRepository activityRepository;
	private final ActivityPostProcessingService postProcessingService;

	public PostProcessNewActivityTasklet(UploadJobContextRegistry contextRegistry,
			ActivityRepository activityRepository, ActivityPostProcessingService postProcessingService) {
		this.contextRegistry = contextRegistry;
		this.activityRepository = activityRepository;
		this.postProcessingService = postProcessingService;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);

		Activity parent = null;
		int childTssSum = 0;
		for (UploadJobContext.Segment segment : context.getSegments()) {
			Activity activity = activityRepository.findById(segment.activityId())
					.orElseThrow(() -> new NotFoundException("No such activity."));
			applyParseTimeOnlyFields(activity, segment.parsed());

			Double avgLeftBalancePct = averageLeftBalancePct(segment.parsed());
			postProcessingService.processNewActivity(segment.activityId(), avgLeftBalancePct);

			if (activity.getSport() == Sport.MULTISPORT) {
				parent = activity;
			}
			else {
				Activity reloaded = activityRepository.findById(segment.activityId())
						.orElseThrow(() -> new NotFoundException("No such activity."));
				childTssSum += reloaded.getTss();
			}
		}
		if (parent != null) {
			// The multisport parent's TSS is the sum of its legs' - computing TSS over its own
			// mixed-sport stream directly would need a single threshold that doesn't exist.
			parent.setTss(childTssSum);
			activityRepository.save(parent);
		}
		return RepeatStatus.FINISHED;
	}

	private void applyParseTimeOnlyFields(Activity activity, ParsedActivity parsed) {
		if (parsed.aerobicTrainingEffect() != null || parsed.anaerobicTrainingEffect() != null) {
			activity.setAerobicTrainingEffect(parsed.aerobicTrainingEffect());
			activity.setAnaerobicTrainingEffect(parsed.anaerobicTrainingEffect());
			activity.setTrainingEffectLabel(TrainingEffectLabel.of(parsed.aerobicTrainingEffect()));
			activityRepository.save(activity);
		}
	}

	private Double averageLeftBalancePct(ParsedActivity parsed) {
		List<Double> leftBalanceSeries = parsed.samples().stream().map(ParsedActivity.Sample::leftBalancePct).toList();
		OptionalDouble avg = leftBalanceSeries.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
		return avg.isPresent() ? Math.round(avg.getAsDouble() * 10) / 10.0 : null;
	}
}
