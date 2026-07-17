package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.BestEffortWindows;
import com.cadence.api.activities.DurationCurve;
import com.cadence.api.activities.DurationCurveMetric;
import com.cadence.api.activities.DurationCurveRepository;
import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@StepScope
public class DurationCurveTasklet implements Tasklet {

	private final UploadJobContext context;
	private final ActivityRepository activityRepository;
	private final DurationCurveRepository durationCurveRepository;

	public DurationCurveTasklet(UploadJobContext context, ActivityRepository activityRepository,
			DurationCurveRepository durationCurveRepository) {
		this.context = context;
		this.activityRepository = activityRepository;
		this.durationCurveRepository = durationCurveRepository;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
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
			int n = powerSeries.size();

			if (powerSeries.stream().anyMatch(Objects::nonNull)) {
				writeCurve(activity, DurationCurveMetric.POWER, powerSeries, BestEffortWindows.POWER_CURVE_DURATIONS, n);
			}
			if (hrSeries.stream().anyMatch(Objects::nonNull)) {
				writeCurve(activity, DurationCurveMetric.HEARTRATE, hrSeries, BestEffortWindows.HR_CURVE_DURATIONS, n);
			}
		}
		return RepeatStatus.FINISHED;
	}

	private void writeCurve(Activity activity, DurationCurveMetric metric, List<Integer> series, List<Integer> durations, int n) {
		Map<Integer, Double> points = DurationCurveCalculator.compute(series, durations);
		if (points.isEmpty()) {
			return;
		}
		Map<String, Double> stringKeyedPoints = new LinkedHashMap<>();
		points.forEach((duration, value) -> stringKeyedPoints.put(String.valueOf(duration), value));

		DurationCurve curve = durationCurveRepository.findByActivityIdAndMetric(activity.getId(), metric).orElseGet(DurationCurve::new);
		curve.setActivity(activity);
		curve.setMetric(metric);
		curve.setExtendsTo(n);
		curve.setPoints(stringKeyedPoints);
		durationCurveRepository.save(curve);
	}
}
