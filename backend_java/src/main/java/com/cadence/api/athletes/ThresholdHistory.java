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
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per (athlete, field) every time the rolling-window-derived threshold value actually
 * changes - see {@code ThresholdHistoryService}. The sole source of truth for "what was this
 * athlete's threshold at any given point in time": an activity's own effective threshold is a
 * lookup here (the most recent entry with effectiveFrom &lt;= that activity's start date), not a
 * value duplicated onto every Activity row. Never addressed by its own id through the API.
 */
@Entity
@Table(name = "threshold_history")
public class ThresholdHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private ThresholdField field;

	// Dual-typed like the fields this replaces (Activity.ftpSnapshot vs thresholdPaceSnapshot,
	// User.ftp vs thresholdPace): valueNumeric for ftp/criticalRunPower, valuePace ("M:SS") for
	// thresholdPace - only one is ever populated, matching which `field` this row is.
	@Column(name = "value_numeric")
	private Integer valueNumeric;

	@Column(name = "value_pace", nullable = false)
	private String valuePace = "";

	// Null for a manually-entered value (see ThresholdHistoryService.recordManualValue) - the
	// athlete declared it directly via their profile, not from a specific activity's effort.
	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "source_activity_id", nullable = true)
	private Activity sourceActivity;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

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

	public Integer getValueNumeric() {
		return valueNumeric;
	}

	public void setValueNumeric(Integer valueNumeric) {
		this.valueNumeric = valueNumeric;
	}

	public String getValuePace() {
		return valuePace;
	}

	public void setValuePace(String valuePace) {
		this.valuePace = valuePace;
	}

	public Activity getSourceActivity() {
		return sourceActivity;
	}

	public void setSourceActivity(Activity sourceActivity) {
		this.sourceActivity = sourceActivity;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public void setEffectiveFrom(LocalDate effectiveFrom) {
		this.effectiveFrom = effectiveFrom;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
