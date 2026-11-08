package com.cadence.api.activities;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Issue #503 (phase 1): a non-primary recorded stream kept alongside an activity - e.g. the
 * Garmin/pedal power copy of a ride whose Zwift/Kickr copy is primary. The primary stream itself
 * is never represented here - it stays in {@link Record}'s own columns, so every existing
 * calculator/curve/best-effort/threshold path is unchanged. One row per stream (not one row per
 * second) to keep storage small - see the issue's own sizing rationale. Mirrors backend's
 * activities/models.py ActivitySource - keep the two in sync.
 */
@Entity
@Table(name = "activity_source")
public class ActivitySource extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	private String label;

	private String device;

	/** What field this source provides a stream for, e.g. "power" - phase 1 only ever creates
	 * "power" sources; stored as a plain string (not a fields-whitelist enum) for the same
	 * reason merge's own `fields` list is, so a later phase (HR, cadence) needs no schema change. */
	@Column(name = "sensor_kind", nullable = false)
	private String sensorKind;

	@Column(nullable = false)
	private ActivitySourceOrigin origin;

	/** Issue #503 (phase 2): for a {@code sensorKind == "power"} source on a RUN activity only -
	 * which running-power candidate ({@code RunningPowerSource.NATIVE}/{@code STRYD}'s own wire
	 * value) this stream holds. Null for every bike-sourced row (bike distinguishes pedals from
	 * trainer by {@link #device} via {@code ActivitySourceService#classify} instead - a run's two
	 * candidates come from the *same* device, so there's nothing to classify by device string)
	 * and for any non-power sensorKind. */
	@Column(name = "running_power_kind")
	private String runningPowerKind;

	@Column(nullable = false)
	private Instant createdAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "src";
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public String getSensorKind() {
		return sensorKind;
	}

	public void setSensorKind(String sensorKind) {
		this.sensorKind = sensorKind;
	}

	public ActivitySourceOrigin getOrigin() {
		return origin;
	}

	public void setOrigin(ActivitySourceOrigin origin) {
		this.origin = origin;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getRunningPowerKind() {
		return runningPowerKind;
	}

	public void setRunningPowerKind(String runningPowerKind) {
		this.runningPowerKind = runningPowerKind;
	}
}
