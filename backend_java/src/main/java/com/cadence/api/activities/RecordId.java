package com.cadence.api.activities;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Embeddable
public class RecordId implements Serializable {

	private String activityId;
	private Instant ts;

	public RecordId() {
	}

	public RecordId(String activityId, Instant ts) {
		this.activityId = activityId;
		this.ts = ts;
	}

	public String getActivityId() {
		return activityId;
	}

	public void setActivityId(String activityId) {
		this.activityId = activityId;
	}

	public Instant getTs() {
		return ts;
	}

	public void setTs(Instant ts) {
		this.ts = ts;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RecordId other)) {
			return false;
		}
		return Objects.equals(activityId, other.activityId) && Objects.equals(ts, other.ts);
	}

	@Override
	public int hashCode() {
		return Objects.hash(activityId, ts);
	}
}
