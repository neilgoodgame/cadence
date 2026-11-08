package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Issue #503 (phase 1): one {@link ActivitySource}'s own recorded values for one field, 1 Hz on
 * the activity's own timeline (the same {@code t} {@link Record} uses, starting at {@code t0}) -
 * one row per stream, not one row per second. {@code null} in {@code streamValues} at index
 * {@code i} means the source had no sample at {@code t0 + i} (a gap), matching how {@link
 * Record}'s own fields are nullable per-second. Mirrors backend's activities/models.py
 * ActivityStream - keep the two in sync.
 */
@Entity
@Table(name = "activity_stream")
public class ActivityStream {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(nullable = false)
	private String field;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private ActivitySource source;

	@Column(nullable = false)
	private int t0;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "stream_values", nullable = false)
	private Short[] streamValues;

	/** Always false today - a primary stream lives in {@link Record} instead, never here; see
	 * the owning migration's own comment for why the column exists regardless. */
	@Column(name = "primary_of", nullable = false)
	private boolean primaryOf;

	public Long getId() {
		return id;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public ActivitySource getSource() {
		return source;
	}

	public void setSource(ActivitySource source) {
		this.source = source;
	}

	public int getT0() {
		return t0;
	}

	public void setT0(int t0) {
		this.t0 = t0;
	}

	public Short[] getStreamValues() {
		return streamValues;
	}

	public void setStreamValues(Short[] streamValues) {
		this.streamValues = streamValues;
	}

	public boolean isPrimaryOf() {
		return primaryOf;
	}

	public void setPrimaryOf(boolean primaryOf) {
		this.primaryOf = primaryOf;
	}
}
