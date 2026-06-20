package com.cadence.api.workouts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Never addressed by its own id through the API - always part of a {@link Workout}'s ordered step list. */
@Entity
@Table(name = "workout_step")
public class WorkoutStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workout_id", nullable = false)
	private Workout workout;

	@Column(name = "step_order", nullable = false)
	private int order;

	@Column(nullable = false)
	private StepKind kind;

	@Column(name = "end_type", nullable = false)
	private StepEndType endType;

	private Integer duration;

	private Integer distance;

	@Column(name = "target_pct")
	private Double targetPct;

	@Column(nullable = false)
	private int repeat = 1;

	public Long getId() {
		return id;
	}

	public Workout getWorkout() {
		return workout;
	}

	public void setWorkout(Workout workout) {
		this.workout = workout;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public StepKind getKind() {
		return kind;
	}

	public void setKind(StepKind kind) {
		this.kind = kind;
	}

	public StepEndType getEndType() {
		return endType;
	}

	public void setEndType(StepEndType endType) {
		this.endType = endType;
	}

	public Integer getDuration() {
		return duration;
	}

	public void setDuration(Integer duration) {
		this.duration = duration;
	}

	public Integer getDistance() {
		return distance;
	}

	public void setDistance(Integer distance) {
		this.distance = distance;
	}

	public Double getTargetPct() {
		return targetPct;
	}

	public void setTargetPct(Double targetPct) {
		this.targetPct = targetPct;
	}

	public int getRepeat() {
		return repeat;
	}

	public void setRepeat(int repeat) {
		this.repeat = repeat;
	}
}
