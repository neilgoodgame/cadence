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
 * lookup here (the most recent entry with currentFrom &lt;= that activity's start date), not a
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

	// The qualifying activity's own date - what "this activity set/previously defined your X"
	// display is keyed on. NOT necessarily the date this row started being the recorded current
	// value - see currentFrom below for that. Equal to currentFrom for the common case (this
	// candidate wins immediately, on its own date), but can be much earlier than currentFrom when
	// this row only became current later, via an *earlier* better entry aging out of the window
	// (the "not a one-way ratchet" case - see ThresholdHistoryCalculator.currentWindowValue's
	// docstring). A row dated e.g. 2023-09-03 that only overtook a better 2023-08-26 entry once
	// that one aged out 112 days later is a real, confirmed example - not a hypothetical.
	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	// The date this row actually became the recorded current value - the date of whichever
	// activity's ingest/recompute pass first found this to be the new window winner (for a
	// cascading-expiry win, that's a *different*, later activity than effectiveFrom's own one;
	// for the common immediate-win case, the two are equal). An activity-scoped lookup
	// (ZoneService.referenceFor) must filter on this, not effectiveFrom - filtering on
	// effectiveFrom lets a not-yet-current row match its own activity's date, since
	// effectiveFrom <= that same date trivially holds, even though the row wasn't actually in
	// effect until much later.
	@Column(name = "current_from", nullable = false)
	private LocalDate currentFrom;

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

	public LocalDate getCurrentFrom() {
		return currentFrom;
	}

	public void setCurrentFrom(LocalDate currentFrom) {
		this.currentFrom = currentFrom;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
