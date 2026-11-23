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
 * Issue #540 (phase 6): one applied repair - replaces {@code activity}'s primary stream for
 * {@code field}, at the given segments, with {@code fromSource}'s own stored reading there.
 * Always patches the *primary* stream (never a stored {@link ActivitySource}) - repair targets
 * what the athlete trains/races by; patching a non-primary stored source has no use case here
 * and isn't supported. Reversible via an unpatch as long as this row exists - {@code
 * previousValues} snapshots exactly what primary held before the patch, since (unlike {@link
 * Merge}'s overwrite rows) there's no stored upload file to re-parse for an arbitrary
 * flagged-segment edit. Mirrors backend's activities/models.py StreamPatch model - keep the two
 * in sync when changing either.
 */
@Entity
@Table(name = "stream_patch")
public class StreamPatch extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	// Reuse ActivitySourceService.VALID_FIELDS ("power", "heartrate", "cadence") rather than a
	// separate enum here - same reasoning as the Django model's own comment.
	@Column(nullable = false, length = 20)
	private String field;

	/** No {@code ON DELETE} clause on the underlying FK (see the V108 migration) - deliberate
	 * difference from {@link Merge#getAttachedSource}'s SET_NULL: a StreamPatch without knowing
	 * which source it copied from can't be meaningfully unpatched, so block the delete instead
	 * of letting the audit row go silently unrestorable. Accepted consequence: unpatch sets
	 * {@code undoneAt} but keeps the row, so this keeps blocking fromSource deletion even for a
	 * fully-undone patch - a source ever used in any patch is permanently undeletable. Accepted
	 * deliberately (see the design doc's own "accepted consequence" note) - if this ever
	 * actually bites, delete the stale StreamPatch row then (admin/manual) rather than weakening
	 * this constraint. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "from_source_id", nullable = false)
	private ActivitySource fromSource;

	/** "contact_loss" or "cadence_lock" when segments came from a live streams/flags detector
	 * result at patch time; null when the caller passed explicit segments instead - the
	 * detector's own flags are never persisted (recomputed live, same as DropoutDetector's
	 * existing stance), so this just records which detector (if any) produced them, not a
	 * reference back to a stored flag row. */
	@Column(name = "detected_kind", length = 20)
	private String detectedKind;

	/** {@code [[startT, endT], ...]} - inclusive, applied. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<List<Integer>> segments;

	/** {@code {t (as string): originalValueOrNull}} for exactly the patched seconds - small
	 * (bounded by segment length, not the whole stream), unlike Merge's file-reparse restore
	 * path. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> previousValues;

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
		return "pat";
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

	public ActivitySource getFromSource() {
		return fromSource;
	}

	public void setFromSource(ActivitySource fromSource) {
		this.fromSource = fromSource;
	}

	public String getDetectedKind() {
		return detectedKind;
	}

	public void setDetectedKind(String detectedKind) {
		this.detectedKind = detectedKind;
	}

	public List<List<Integer>> getSegments() {
		return segments;
	}

	public void setSegments(List<List<Integer>> segments) {
		this.segments = segments;
	}

	public Map<String, Object> getPreviousValues() {
		return previousValues;
	}

	public void setPreviousValues(Map<String, Object> previousValues) {
		this.previousValues = previousValues;
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
