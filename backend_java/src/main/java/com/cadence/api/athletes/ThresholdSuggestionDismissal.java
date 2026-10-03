package com.cadence.api.athletes;

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
 * An athlete dismissed one specific Threshold suggestion occurrence - see
 * {@code ThresholdSuggestionService}. {@code key} identifies the *specific* occurrence (a
 * candidate activity id for "rejected"; a {@code "{sourceActivityId}:{expiryDate}"} pair for
 * "upcoming_drop"), not just the field+kind, so dismissing one doesn't silence a genuinely
 * different future occurrence of the same kind for the same field.
 */
@Entity
@Table(name = "threshold_suggestion_dismissal",
		uniqueConstraints = @UniqueConstraint(name = "unique_threshold_suggestion_dismissal", columnNames = { "athlete_id", "field", "kind", "key" }))
public class ThresholdSuggestionDismissal {

	public enum Kind {
		REJECTED, UPCOMING_DROP
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private ThresholdField field;

	@Column(nullable = false)
	private Kind kind;

	@Column(nullable = false)
	private String key;

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

	public Kind getKind() {
		return kind;
	}

	public void setKind(Kind kind) {
		this.kind = kind;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
