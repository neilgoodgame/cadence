package com.cadence.api.garmin;

import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A Cadence scheduled workout and its entry on the Garmin calendar - see V87. */
@Entity
@Table(name = "garmin_schedule_link")
public class GarminScheduleLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(name = "scheduled_workout_id", nullable = false)
	private String scheduledWorkoutId;

	@Column(name = "garmin_schedule_id", nullable = false)
	private long garminScheduleId;

	@Column(name = "garmin_workout_id", nullable = false)
	private long garminWorkoutId;

	@Column(name = "scheduled_for", nullable = false)
	private LocalDate scheduledFor;

	protected GarminScheduleLink() {
	}

	public GarminScheduleLink(User owner, String scheduledWorkoutId, long garminScheduleId, long garminWorkoutId,
			LocalDate scheduledFor) {
		this.owner = owner;
		this.scheduledWorkoutId = scheduledWorkoutId;
		this.garminScheduleId = garminScheduleId;
		this.garminWorkoutId = garminWorkoutId;
		this.scheduledFor = scheduledFor;
	}

	public String getScheduledWorkoutId() {
		return scheduledWorkoutId;
	}

	public long getGarminScheduleId() {
		return garminScheduleId;
	}

	public long getGarminWorkoutId() {
		return garminWorkoutId;
	}

	public LocalDate getScheduledFor() {
		return scheduledFor;
	}

	public void update(long garminScheduleId, long garminWorkoutId, LocalDate scheduledFor) {
		this.garminScheduleId = garminScheduleId;
		this.garminWorkoutId = garminWorkoutId;
		this.scheduledFor = scheduledFor;
	}
}
