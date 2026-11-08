package com.cadence.api.activities;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Issue #501 (phase 4): a saved per-athlete, per-sport rule for automatic merging - which side
 * plays target/source, which fields to copy, and the null policy. Consulted by {@link
 * ActivityOverlapDetectionService} when resolving an {@code OverlapHandling.MERGE} pair; no row
 * for a sport means {@link MergeRuleService}'s built-in default applies instead (or, for a sport
 * with no built-in default, that automatic merging simply doesn't apply - the pair is flagged).
 *
 * <p>{@code targetPattern}/{@code sourcePattern} are {@code "device:<prefix>"} strings (e.g.
 * {@code "device:zwift"}), matched case-insensitively as a prefix against {@link
 * Activity#getDevice()} - see {@link MergeRuleService#matches}. Only {@code device:} patterns
 * are supported today; the prefix exists so other pattern kinds could be added later without a
 * schema change. Mirrors backend's activities/models.py MergeRule - keep the two in sync.
 */
@Entity
@Table(name = "merge_rule", uniqueConstraints = @UniqueConstraint(columnNames = { "athlete_id", "sport" }))
public class MergeRule extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Sport sport;

	@Column(nullable = false, length = 100)
	private String targetPattern;

	@Column(nullable = false, length = 100)
	private String sourcePattern;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<String> fields;

	@Column(nullable = false)
	private NullPolicy nullPolicy;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@PrePersist
	private void onCreate() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	private void onUpdate() {
		updatedAt = Instant.now();
	}

	@Override
	protected String idPrefix() {
		return "mrl";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public Sport getSport() {
		return sport;
	}

	public void setSport(Sport sport) {
		this.sport = sport;
	}

	public String getTargetPattern() {
		return targetPattern;
	}

	public void setTargetPattern(String targetPattern) {
		this.targetPattern = targetPattern;
	}

	public String getSourcePattern() {
		return sourcePattern;
	}

	public void setSourcePattern(String sourcePattern) {
		this.sourcePattern = sourcePattern;
	}

	public List<String> getFields() {
		return fields;
	}

	public void setFields(List<String> fields) {
		this.fields = fields;
	}

	public NullPolicy getNullPolicy() {
		return nullPolicy;
	}

	public void setNullPolicy(NullPolicy nullPolicy) {
		this.nullPolicy = nullPolicy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
