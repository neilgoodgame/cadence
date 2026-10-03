package com.cadence.api.athletes;

import com.cadence.api.activities.Activity;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * An athlete accepted a "rejected" Threshold suggestion - the candidate activity's implied value
 * beat the sanity band, and the athlete confirmed it's real, not a sensor glitch. Must survive
 * {@link ThresholdHistoryCalculator#replayFullHistory} (which re-applies the sanity band against
 * a *moving* reference and would reject the same candidate again without this override) - see
 * that class's {@code rejectedCandidates}/{@code currentWindowValue}, which bypass the band for
 * an accepted (field, activityId).
 */
@Entity
@Table(name = "accepted_threshold_candidate",
		uniqueConstraints = @UniqueConstraint(name = "unique_accepted_threshold_candidate", columnNames = { "athlete_id", "field", "activity_id" }))
public class AcceptedThresholdCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private ThresholdField field;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@jakarta.persistence.PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public ThresholdField getField() {
		return field;
	}

	public void setField(ThresholdField field) {
		this.field = field;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
