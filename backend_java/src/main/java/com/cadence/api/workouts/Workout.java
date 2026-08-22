package com.cadence.api.workouts;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workout")
public class Workout extends PrefixedIdEntity {

	/** Internal scoping/permission reference only - never serialized. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by", nullable = false)
	private User createdBy;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private Sport sport;

	/** Free-text classification (e.g. "vo2") - never writable via the API, stays blank. */
	@Column(nullable = false)
	private String type = "";

	@Column(nullable = false)
	private int duration;

	@Column(nullable = false)
	private int tss;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "folder_id")
	private WorkoutFolder folder;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<String> tags = new ArrayList<>();

	/** Flattened per-leaf average target intensity and duration, recomputed alongside
	 * duration/tss whenever steps are replaced - cheap chart data for library cards/rows
	 * without shipping the full step tree in the list response. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<ChartPreviewPoint> chartPreview = new ArrayList<>();

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "workout", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("order ASC")
	private List<WorkoutStep> steps = new ArrayList<>();

	@Override
	protected String idPrefix() {
		return "wkt";
	}

	public User getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(User createdBy) {
		this.createdBy = createdBy;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Sport getSport() {
		return sport;
	}

	public void setSport(Sport sport) {
		this.sport = sport;
	}

	public String getType() {
		return type;
	}

	public int getDuration() {
		return duration;
	}

	public void setDuration(int duration) {
		this.duration = duration;
	}

	public int getTss() {
		return tss;
	}

	public void setTss(int tss) {
		this.tss = tss;
	}

	public WorkoutFolder getFolder() {
		return folder;
	}

	public void setFolder(WorkoutFolder folder) {
		this.folder = folder;
	}

	public List<String> getTags() {
		return tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags;
	}

	public List<ChartPreviewPoint> getChartPreview() {
		return chartPreview;
	}

	public void setChartPreview(List<ChartPreviewPoint> chartPreview) {
		this.chartPreview = chartPreview;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public List<WorkoutStep> getSteps() {
		return steps;
	}
}
