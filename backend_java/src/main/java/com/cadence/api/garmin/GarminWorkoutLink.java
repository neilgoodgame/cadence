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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A Cadence workout and the Garmin workout it was pushed as - see V87. */
@Entity
@Table(name = "garmin_workout_link")
public class GarminWorkoutLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(name = "workout_id", nullable = false)
	private String workoutId;

	@Column(name = "garmin_workout_id", nullable = false)
	private long garminWorkoutId;

	@Column(name = "pushed_at", nullable = false)
	private Instant pushedAt;

	protected GarminWorkoutLink() {
	}

	public GarminWorkoutLink(User owner, String workoutId, long garminWorkoutId) {
		this.owner = owner;
		this.workoutId = workoutId;
		this.garminWorkoutId = garminWorkoutId;
	}

	@PrePersist
	private void onCreate() {
		if (pushedAt == null) {
			pushedAt = Instant.now();
		}
	}

	public String getWorkoutId() {
		return workoutId;
	}

	public long getGarminWorkoutId() {
		return garminWorkoutId;
	}

	public void setGarminWorkoutId(long garminWorkoutId) {
		this.garminWorkoutId = garminWorkoutId;
		this.pushedAt = Instant.now();
	}

	public Instant getPushedAt() {
		return pushedAt;
	}
}
