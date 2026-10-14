package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An activity's elevation resampled to 10 m from its records (CourseSections.buildElevationSeries),
 * for calculating sections on read. Its own table so activity lists never load the series; no row
 * yet means it hasn't been built, which ActivitySectionsService does on the first sections read.
 * Matches the Python backend's activities.ActivityElevation.
 */
@Entity
@Table(name = "activity_elevation")
public class ActivityElevation {

	@Id
	@Column(name = "activity_id")
	private String activityId;

	@Column(name = "elevation_quality", nullable = false)
	private String elevationQuality;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "elevation_series", nullable = false)
	private List<Double> elevationSeries = new ArrayList<>();

	protected ActivityElevation() {}

	public ActivityElevation(String activityId) {
		this.activityId = activityId;
	}

	public String getActivityId() { return activityId; }

	public String getElevationQuality() { return elevationQuality; }

	public void setElevationQuality(String elevationQuality) { this.elevationQuality = elevationQuality; }

	public List<Double> getElevationSeries() { return elevationSeries; }

	public void setElevationSeries(List<Double> elevationSeries) { this.elevationSeries = elevationSeries; }
}
