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

/**
 * Never addressed by its own id through the API - always part of a {@link Workout}'s ordered step
 * tree. A step is either a leaf (warmup/block/rec/cool - has an end condition and a target) or a
 * {@code repeat} group (has {@code repeat} and owns child rows via {@link #parentStep}, which may
 * themselves be nested {@code repeat} groups).
 */
@Entity
@Table(name = "workout_step")
public class WorkoutStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workout_id", nullable = false)
	private Workout workout;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "parent_step_id")
	private WorkoutStep parentStep;

	@Column(name = "step_order", nullable = false)
	private int order;

	@Column(nullable = false)
	private StepKind kind;

	@Column(name = "end_type")
	private StepEndType endType;

	private Integer duration;

	private Integer distance;

	@Column(name = "target_type")
	private TargetType targetType;

	@Column(name = "target_low")
	private Double targetLow;

	@Column(name = "target_high")
	private Double targetHigh;

	@Column(name = "target2_type", nullable = false)
	private Target2Type target2Type = Target2Type.NONE;

	@Column(name = "target2_low")
	private Double target2Low;

	@Column(name = "target2_high")
	private Double target2High;

	@Column(nullable = false)
	private int repeat = 1;

	@Column(nullable = false)
	private String note = "";

	public Long getId() {
		return id;
	}

	public Workout getWorkout() {
		return workout;
	}

	public void setWorkout(Workout workout) {
		this.workout = workout;
	}

	public WorkoutStep getParentStep() {
		return parentStep;
	}

	public void setParentStep(WorkoutStep parentStep) {
		this.parentStep = parentStep;
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

	public TargetType getTargetType() {
		return targetType;
	}

	public void setTargetType(TargetType targetType) {
		this.targetType = targetType;
	}

	public Double getTargetLow() {
		return targetLow;
	}

	public void setTargetLow(Double targetLow) {
		this.targetLow = targetLow;
	}

	public Double getTargetHigh() {
		return targetHigh;
	}

	public void setTargetHigh(Double targetHigh) {
		this.targetHigh = targetHigh;
	}

	public Target2Type getTarget2Type() {
		return target2Type;
	}

	public void setTarget2Type(Target2Type target2Type) {
		this.target2Type = target2Type;
	}

	public Double getTarget2Low() {
		return target2Low;
	}

	public void setTarget2Low(Double target2Low) {
		this.target2Low = target2Low;
	}

	public Double getTarget2High() {
		return target2High;
	}

	public void setTarget2High(Double target2High) {
		this.target2High = target2High;
	}

	public int getRepeat() {
		return repeat;
	}

	public void setRepeat(int repeat) {
		this.repeat = repeat;
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}
}

