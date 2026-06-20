package com.cadence.api.scheduling;

import com.cadence.api.activities.Activity;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import com.cadence.api.workouts.Workout;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "scheduled_workout")
public class ScheduledWorkout extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workout_id", nullable = false)
	private Workout workout;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	/** Null if the athlete scheduled it themself; the coach who assigned it otherwise. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assigned_by")
	private User assignedBy;

	@Column(nullable = false)
	private LocalDate date;

	// AM/MID/PM matches the wire format exactly, so the standard JPA enum mapping needs no
	// custom converter here (unlike every lowercase-wire-format enum elsewhere in this codebase).
	@Enumerated(EnumType.STRING)
	@Column(name = "time_of_day")
	private TimeOfDay timeOfDay;

	@Column(nullable = false)
	private ScheduledWorkoutStatus status = ScheduledWorkoutStatus.PLANNED;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "activity_id")
	private Activity activity;

	@Override
	protected String idPrefix() {
		return "sch";
	}

	public Workout getWorkout() {
		return workout;
	}

	public void setWorkout(Workout workout) {
		this.workout = workout;
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public User getAssignedBy() {
		return assignedBy;
	}

	public void setAssignedBy(User assignedBy) {
		this.assignedBy = assignedBy;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public TimeOfDay getTimeOfDay() {
		return timeOfDay;
	}

	public void setTimeOfDay(TimeOfDay timeOfDay) {
		this.timeOfDay = timeOfDay;
	}

	public ScheduledWorkoutStatus getStatus() {
		return status;
	}

	public void setStatus(ScheduledWorkoutStatus status) {
		this.status = status;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}
}
