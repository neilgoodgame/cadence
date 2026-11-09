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
 * Issue #503 (phases 1-3): a non-primary recorded stream kept alongside an activity - e.g. the
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

	/** What field this source provides a stream for - "power", "heartrate", or "cadence" as of
	 * phase 3; stored as a plain string (not a fields-whitelist enum) for the same reason merge's
	 * own `fields` list is, so a later phase needs no schema change. */
	@Column(name = "sensor_kind", nullable = false)
	private String sensorKind;

	@Column(nullable = false)
	private ActivitySourceOrigin origin;

	/** Issue #503 (phase 3, generalized from phase 2's running-power-only {@code
	 * runningPowerKind}): a free-form tag on what kind of sensor this stream came from, meaning
	 * depends on {@link #sensorKind} - {@code native}/{@code stryd} for a running power source
	 * (set automatically at parse time, see {@code ActivitySourceService
	 * #createParseTimeRunningPowerSource}), {@code strap}/{@code wrist} for heartrate, {@code
	 * pedals}/{@code trainer}/{@code watch} for cadence (both manually supplied by the athlete at
	 * attach time, see {@code ActivitySourceService#attachStream} - there's no automatic
	 * classification signal for either). Null for a bike power source (which distinguishes
	 * pedals/trainer by {@link #device} via {@code ActivitySourceService#classify} instead - a
	 * run's two candidates come from the *same* device, so there's nothing to classify by device
	 * string there) and for any source never tagged at attach/parse time. */
	@Column(name = "source_kind")
	private String sourceKind;

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

	public String getSourceKind() {
		return sourceKind;
	}

	public void setSourceKind(String sourceKind) {
		this.sourceKind = sourceKind;
	}
}
