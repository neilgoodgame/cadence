package com.cadence.api.activities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DurationCurveRepository extends JpaRepository<DurationCurve, Long> {

	Optional<DurationCurve> findByActivityIdAndMetric(String activityId, DurationCurveMetric metric);
}
