package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "lap")
public class Lap {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(name = "lap_index", nullable = false)
	private int index;

	@Column(nullable = false)
	private int duration;

	@Column(name = "distance_km", nullable = false)
	private double distanceKm;

	@Column(name = "avg_hr")
	private Integer avgHr;

	@Column(name = "avg_power")
	private Integer avgPower;

	public Long getId() {
		return id;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public int getDuration() {
		return duration;
	}

	public void setDuration(int duration) {
		this.duration = duration;
	}

	public double getDistanceKm() {
		return distanceKm;
	}

	public void setDistanceKm(double distanceKm) {
		this.distanceKm = distanceKm;
	}

	public Integer getAvgHr() {
		return avgHr;
	}

	public void setAvgHr(Integer avgHr) {
		this.avgHr = avgHr;
	}

	public Integer getAvgPower() {
		return avgPower;
	}

	public void setAvgPower(Integer avgPower) {
		this.avgPower = avgPower;
	}
}
