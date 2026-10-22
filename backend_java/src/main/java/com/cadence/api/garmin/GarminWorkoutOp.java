package com.cadence.api.garmin;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** One thing to do on Garmin Connect, applied by the athlete's local bridge - see V87. */
@Entity
@Table(name = "garmin_workout_op")
public class GarminWorkoutOp extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GarminWorkoutOpKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GarminWorkoutOpStatus status = GarminWorkoutOpStatus.PENDING;

	@Column(name = "workout_id", nullable = false)
	private String workoutId;

	@Column(name = "scheduled_workout_id")
	private String scheduledWorkoutId;

	@Column(name = "op_date")
	private LocalDate date;

	private String name;

	@Column(columnDefinition = "text")
	private String tcx;

	@Column(name = "garmin_workout_id")
	private Long garminWorkoutId;

	@Column(name = "garmin_schedule_id")
	private Long garminScheduleId;

	@Column(columnDefinition = "text")
	private String error;

	@Column(nullable = false)
	private Instant created;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Override
	protected String idPrefix() {
		return "gwo";
	}

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	public User getOwner() {
		return owner;
	}

	public void setOwner(User owner) {
		this.owner = owner;
	}

	public GarminWorkoutOpKind getKind() {
		return kind;
	}

	public void setKind(GarminWorkoutOpKind kind) {
		this.kind = kind;
	}

	public GarminWorkoutOpStatus getStatus() {
		return status;
	}

	public void setStatus(GarminWorkoutOpStatus status) {
		this.status = status;
	}

	public String getWorkoutId() {
		return workoutId;
	}

	public void setWorkoutId(String workoutId) {
		this.workoutId = workoutId;
	}

	public String getScheduledWorkoutId() {
		return scheduledWorkoutId;
	}

	public void setScheduledWorkoutId(String scheduledWorkoutId) {
		this.scheduledWorkoutId = scheduledWorkoutId;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTcx() {
		return tcx;
	}

	public void setTcx(String tcx) {
		this.tcx = tcx;
	}

	public Long getGarminWorkoutId() {
		return garminWorkoutId;
	}

	public void setGarminWorkoutId(Long garminWorkoutId) {
		this.garminWorkoutId = garminWorkoutId;
	}

	public Long getGarminScheduleId() {
		return garminScheduleId;
	}

	public void setGarminScheduleId(Long garminScheduleId) {
		this.garminScheduleId = garminScheduleId;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public Instant getCreated() {
		return created;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}
}
