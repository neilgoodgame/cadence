package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-activity power/heart-rate histograms for the threshold-decay load term (issue #485,
 * docs/plans/threshold-decay.md section 2.3) - seconds spent at each whole watt of 30s-smoothed
 * power, and at each whole bpm of heart rate. Its own table, same reasoning as {@link
 * ActivityElevation}: only the decay load calculation needs these, so they shouldn't bloat every
 * Activity row/query. No row yet means it hasn't been built, which
 * ActivityIntensityHistogramService.ensureHistogram does on first read. Matches the Python
 * backend's activities.ActivityIntensityHistogram.
 */
@Entity
@Table(name = "activity_intensity_histogram")
public class ActivityIntensityHistogram {

	@Id
	@Column(name = "activity_id")
	private String activityId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "power_30s")
	private Map<Integer, Integer> power30s;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "heartrate")
	private Map<Integer, Integer> heartrate;

	protected ActivityIntensityHistogram() {}

	public ActivityIntensityHistogram(String activityId) {
		this.activityId = activityId;
	}

	public String getActivityId() { return activityId; }

	public Map<Integer, Integer> getPower30s() { return power30s; }

	public void setPower30s(Map<Integer, Integer> power30s) { this.power30s = power30s; }

	public Map<Integer, Integer> getHeartrate() { return heartrate; }

	public void setHeartrate(Map<Integer, Integer> heartrate) { this.heartrate = heartrate; }
}
