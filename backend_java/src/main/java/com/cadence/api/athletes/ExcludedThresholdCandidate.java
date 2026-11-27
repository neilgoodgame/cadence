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
 * An athlete (or a coach with write access) excluded this activity from threshold detection for
 * one field - its data is known to be wrong for that field (issue #616, e.g. a ride on an
 * uncalibrated power meter). The mirror image of {@link AcceptedThresholdCandidate}: an excluded
 * activity is never a candidate in any {@link ThresholdHistoryCalculator} scan or decay
 * resimulation, even across a full rebuild, and wins over an acceptance of the same
 * (field, activity). Its decay load comes from its heart rate instead of its power (see
 * {@link DecayLoadService}). Per field, so a run with a bad power sensor can still count for
 * pace. Mirrors the Python backend's athletes.models.ExcludedThresholdCandidate.
 */
@Entity
@Table(name = "excluded_threshold_candidate",
		uniqueConstraints = @UniqueConstraint(name = "unique_excluded_threshold_candidate", columnNames = { "athlete_id", "field", "activity_id" }))
public class ExcludedThresholdCandidate {

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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "set_by_id")
	private User setBy;

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

	public User getSetBy() {
		return setBy;
	}

	public void setSetBy(User setBy) {
		this.setBy = setBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
