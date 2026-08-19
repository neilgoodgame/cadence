package com.cadence.api.activities;

import com.cadence.api.activities.calc.DurationCurveCalculator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Core duration-curve computation shared by the upload pipeline and the on-demand recompute
 * endpoint. Callers are responsible for transaction boundaries, for supplying the time-series
 * data, and for skipping {@code Sport.MULTISPORT} parents - a mixed-sport stream compares
 * incomparable efforts (see DurationCurveTasklet/DurationCurveRecomputeService).
 */
@Service
public class DurationCurveComputeService {

	private final DurationCurveRepository durationCurveRepository;

	public DurationCurveComputeService(DurationCurveRepository durationCurveRepository) {
		this.durationCurveRepository = durationCurveRepository;
	}

	/** Computes and persists whichever of power/HR curves the activity has data for. */
	public void computeForActivity(Activity activity, List<Integer> powerSeries, List<Integer> hrSeries) {
		if (powerSeries.stream().anyMatch(Objects::nonNull)) {
			writeCurve(activity, DurationCurveMetric.POWER, powerSeries, BestEffortWindows.POWER_CURVE_DURATIONS);
		}
		if (hrSeries.stream().anyMatch(Objects::nonNull)) {
			writeCurve(activity, DurationCurveMetric.HEARTRATE, hrSeries, BestEffortWindows.HR_CURVE_DURATIONS);
		}
	}

	private void writeCurve(Activity activity, DurationCurveMetric metric, List<Integer> series, List<Integer> durations) {
		Map<Integer, Double> points = DurationCurveCalculator.compute(series, durations);
		if (points.isEmpty()) {
			return;
		}
		Map<String, Double> stringKeyedPoints = new LinkedHashMap<>();
		points.forEach((duration, value) -> stringKeyedPoints.put(String.valueOf(duration), value));

		DurationCurve curve = durationCurveRepository.findByActivityIdAndMetric(activity.getId(), metric).orElseGet(DurationCurve::new);
		curve.setActivity(activity);
		curve.setMetric(metric);
		curve.setExtendsTo(series.size());
		curve.setPoints(stringKeyedPoints);
		durationCurveRepository.save(curve);
	}
}
