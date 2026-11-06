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

/**
 * One pair resolved by a {@link DuplicateResolutionBatch}. {@code outcome} mirrors {@link
 * ActivityOverlapDetectionService.OverlapResolution}'s own vocabulary ({@code MARKED}/{@code
 * FLAGGED}) - never {@code MERGED} today, since nothing produces that yet (#501). {@code
 * primaryActivity} is set only when {@code outcome == MARKED} - {@code
 * DuplicateResolutionBatchService#undoBatch}'s own check that the activity's current primary
 * still equals it before reverting, so a pair re-resolved (or hand-edited) since this batch
 * applied is never silently clobbered.
 */
@Entity
@Table(name = "duplicate_resolution_entry")
public class DuplicateResolutionEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "batch_id", nullable = false)
	private DuplicateResolutionBatch batch;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "other_activity_id")
	private Activity otherActivity;

	@Column(nullable = false)
	private OverlapAction outcome;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "primary_activity_id")
	private Activity primaryActivity;

	public Long getId() {
		return id;
	}

	public DuplicateResolutionBatch getBatch() {
		return batch;
	}

	public void setBatch(DuplicateResolutionBatch batch) {
		this.batch = batch;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public Activity getOtherActivity() {
		return otherActivity;
	}

	public void setOtherActivity(Activity otherActivity) {
		this.otherActivity = otherActivity;
	}

	public OverlapAction getOutcome() {
		return outcome;
	}

	public void setOutcome(OverlapAction outcome) {
		this.outcome = outcome;
	}

	public Activity getPrimaryActivity() {
		return primaryActivity;
	}

	public void setPrimaryActivity(Activity primaryActivity) {
		this.primaryActivity = primaryActivity;
	}
}
