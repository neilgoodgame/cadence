package com.cadence.api.activities.dto;

import com.cadence.api.activities.DurationCurveMetric;
import java.util.Map;

public record DurationCurveResponse(DurationCurveMetric metric, int extendsTo, Map<String, Double> points) {
}
