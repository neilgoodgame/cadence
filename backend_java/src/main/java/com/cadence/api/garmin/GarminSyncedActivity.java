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

/** One Garmin activity id pulled for an athlete - see V85 for why it outlives the activity and the run. */
@Entity
@Table(name = "garmin_synced_activity")
public class GarminSyncedActivity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(name = "garmin_activity_id", nullable = false)
	private long garminActivityId;

	@Column(name = "start_time", nullable = false)
	private Instant startTime;

	@Column(name = "run_id")
	private String runId;

	@Column(nullable = false)
	private Instant created;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	public GarminSyncedActivity() {
	}

	public GarminSyncedActivity(User owner, long garminActivityId, Instant startTime, String runId) {
		this.owner = owner;
		this.garminActivityId = garminActivityId;
		this.startTime = startTime;
		this.runId = runId;
	}

	public long getGarminActivityId() {
		return garminActivityId;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public String getRunId() {
		return runId;
	}
}
