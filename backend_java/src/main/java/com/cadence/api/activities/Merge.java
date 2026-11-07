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
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Issue #501: records one applied merge, so it's auditable and - when the target's upload file
 * is still stored - reversible via a future unmerge. Never created for a preview; nothing
 * happened, nothing to record. {@code sourceActivity} is nullable/SET_NULL rather than CASCADE
 * for the same "keep the hidden copy, don't delete it" reversibility #498's own duplicate rows
 * get - if an athlete deletes the source outright anyway, this audit row survives as a record
 * of what happened even though undo is no longer possible. Table name is {@code activity_merge},
 * not {@code merge} - Postgres 15+ reserves {@code MERGE} as a statement keyword. Mirrors
 * backend's activities/models.py Merge model - keep the two in sync when changing either.
 */
@Entity
@Table(name = "activity_merge")
public class Merge extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_activity_id", nullable = false)
	private Activity targetActivity;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_activity_id")
	private Activity sourceActivity;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<String> fields;

	@Column(nullable = false)
	private ActivityMergeApplyService.NullPolicy nullPolicy;

	@Column(nullable = false)
	private int offsetSeconds;

	private Double alignmentScore;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, ActivityMergeApplyService.FieldRewriteCounts> fieldCounts;

	/** The target's own value for each requested *activity-level* field, captured immediately
	 * before {@link ActivityMergeApplyService#applyMergeFields} overwrote it.
	 * {@link ActivityUnmergeService} restores from this rather than re-deriving it (e.g.
	 * regenerating {@code name} from sport+date), so a legitimate edit made between the merge
	 * and its later undo is never silently discarded. Values are plain JSON-safe types - in
	 * particular bikeId/shoeId are stored as their id string, not the entity, since the entity
	 * itself isn't a sensible JSON value (see ActivityMergeApplyService's own snapshot helpers).
	 * Stream fields aren't snapshotted here - undo restores those by re-parsing the target's own
	 * stored upload file instead, which would make snapshotting them here pure redundant
	 * storage. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> previousActivityValues;

	@Column(nullable = false)
	private boolean sourceHidden;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant undoneAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "mrg";
	}

	public Activity getTargetActivity() {
		return targetActivity;
	}

	public void setTargetActivity(Activity targetActivity) {
		this.targetActivity = targetActivity;
	}

	public Activity getSourceActivity() {
		return sourceActivity;
	}

	public void setSourceActivity(Activity sourceActivity) {
		this.sourceActivity = sourceActivity;
	}

	public List<String> getFields() {
		return fields;
	}

	public void setFields(List<String> fields) {
		this.fields = fields;
	}

	public ActivityMergeApplyService.NullPolicy getNullPolicy() {
		return nullPolicy;
	}

	public void setNullPolicy(ActivityMergeApplyService.NullPolicy nullPolicy) {
		this.nullPolicy = nullPolicy;
	}

	public int getOffsetSeconds() {
		return offsetSeconds;
	}

	public void setOffsetSeconds(int offsetSeconds) {
		this.offsetSeconds = offsetSeconds;
	}

	public Double getAlignmentScore() {
		return alignmentScore;
	}

	public void setAlignmentScore(Double alignmentScore) {
		this.alignmentScore = alignmentScore;
	}

	public Map<String, ActivityMergeApplyService.FieldRewriteCounts> getFieldCounts() {
		return fieldCounts;
	}

	public void setFieldCounts(Map<String, ActivityMergeApplyService.FieldRewriteCounts> fieldCounts) {
		this.fieldCounts = fieldCounts;
	}

	public Map<String, Object> getPreviousActivityValues() {
		return previousActivityValues;
	}

	public void setPreviousActivityValues(Map<String, Object> previousActivityValues) {
		this.previousActivityValues = previousActivityValues;
	}

	public boolean isSourceHidden() {
		return sourceHidden;
	}

	public void setSourceHidden(boolean sourceHidden) {
		this.sourceHidden = sourceHidden;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUndoneAt() {
		return undoneAt;
	}

	public void setUndoneAt(Instant undoneAt) {
		this.undoneAt = undoneAt;
	}
}
