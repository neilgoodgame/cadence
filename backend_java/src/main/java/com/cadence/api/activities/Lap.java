package com.cadence.api.activities;

import com.cadence.api.workouts.WorkoutStep;
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

	// Set only for a lap derived from a matched Workout's own step boundaries
	// (LapDerivationService) - null for a device-FIT-parsed lap (LapSource.ORIGINAL), an
	// unmatched activity, or a trailing/leading segment outside the workout's own steps.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workout_step_id")
	private WorkoutStep workoutStep;

	// 1-based - which repetition of workoutStep's containing repeat group this lap came from
	// (workoutStep itself is one DB row regardless of how many times it repeats, so multiple
	// laps legitimately share the same workout_step_id). Null when workoutStep isn't a
	// repeated step, or when workoutStep itself is null.
	@Column(name = "repeat_index")
	private Integer repeatIndex;

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

	public WorkoutStep getWorkoutStep() {
		return workoutStep;
	}

	public void setWorkoutStep(WorkoutStep workoutStep) {
		this.workoutStep = workoutStep;
	}

	public Integer getRepeatIndex() {
		return repeatIndex;
	}

	public void setRepeatIndex(Integer repeatIndex) {
		this.repeatIndex = repeatIndex;
	}
}
