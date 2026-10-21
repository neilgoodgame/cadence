package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Tag;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.courses.Course;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "race")
public class Race extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private LocalDate date;

	private Sport sport;

	// "A"/"B"/"C" matches the wire format exactly, so the standard JPA enum mapping needs no
	// custom converter here (same reasoning as ScheduledWorkout.TimeOfDay).
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RacePriority priority = RacePriority.A;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id")
	private Course course;

	@Column(name = "distance_km")
	private Double distanceKm;

	/** Seconds. */
	@Column(name = "goal_time")
	private Integer goalTime;

	/** Seconds. */
	@Column(name = "result_time")
	private Integer resultTime;

	/** Watts. Used as the splits delta reference (in place of GAP) when the linked activity has
	 * power data - falls back to the activity's own average power when unset. */
	@Column(name = "target_power")
	private Integer targetPower;

	/** A races only - an explicit override for RaceBlockService.resolveBlockWeeks (distance-
	 * bucket default, then athlete.raceBlockWeeks, if unset). Named "...Requested" rather than
	 * "blockWeeks" to avoid colliding with the read-only *computed* block_weeks RaceService
	 * already exposes on the plain Race resource. */
	@Column(name = "block_weeks_requested")
	private Integer blockWeeksRequested;

	/** Explicit Base/Build/Peak/Taper lengths - must sum to blockWeeksRequested exactly (see
	 * RaceService's validation). Null falls back to the proportional RaceBlockService.phases()
	 * split, same as before these fields existed. */
	@Column(name = "base_weeks_requested")
	private Integer baseWeeksRequested;

	@Column(name = "build_weeks_requested")
	private Integer buildWeeksRequested;

	@Column(name = "peak_weeks_requested")
	private Integer peakWeeksRequested;

	@Column(name = "taper_weeks_requested")
	private Integer taperWeeksRequested;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "activity_id")
	private Activity activity;

	private String url;

	@Column(name = "results_url")
	private String resultsUrl;

	@Column(nullable = false)
	private String notes = "";

	// Opt-in by the owner: a public race's result, splits, course and weather are served
	// unauthenticated under /v1/public/races/{id} (see PublicRaceService).
	@Column(name = "is_public", nullable = false)
	private boolean isPublic;

	/** Overrides the athlete-wide "Key session" tag lookup for this race's key_sessions.items
	 * "tagged" category (RaceBlockService.keySessions) - lets different goal races use different
	 * tags instead of sharing the one global tag name. Null falls back to that default. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "key_session_tag_id")
	private Tag keySessionTag;

	@Override
	protected String idPrefix() {
		return "race";
	}

	public User getAthlete() { return athlete; }
	public void setAthlete(User athlete) { this.athlete = athlete; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public LocalDate getDate() { return date; }
	public void setDate(LocalDate date) { this.date = date; }

	public Sport getSport() { return sport; }
	public void setSport(Sport sport) { this.sport = sport; }

	public RacePriority getPriority() { return priority; }
	public void setPriority(RacePriority priority) { this.priority = priority; }

	public Course getCourse() { return course; }
	public void setCourse(Course course) { this.course = course; }

	public Double getDistanceKm() { return distanceKm; }
	public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }

	public Integer getGoalTime() { return goalTime; }
	public void setGoalTime(Integer goalTime) { this.goalTime = goalTime; }

	public Integer getResultTime() { return resultTime; }
	public void setResultTime(Integer resultTime) { this.resultTime = resultTime; }

	public Integer getTargetPower() { return targetPower; }
	public void setTargetPower(Integer targetPower) { this.targetPower = targetPower; }

	public Integer getBlockWeeksRequested() { return blockWeeksRequested; }
	public void setBlockWeeksRequested(Integer blockWeeksRequested) { this.blockWeeksRequested = blockWeeksRequested; }

	public Integer getBaseWeeksRequested() { return baseWeeksRequested; }
	public void setBaseWeeksRequested(Integer baseWeeksRequested) { this.baseWeeksRequested = baseWeeksRequested; }

	public Integer getBuildWeeksRequested() { return buildWeeksRequested; }
	public void setBuildWeeksRequested(Integer buildWeeksRequested) { this.buildWeeksRequested = buildWeeksRequested; }

	public Integer getPeakWeeksRequested() { return peakWeeksRequested; }
	public void setPeakWeeksRequested(Integer peakWeeksRequested) { this.peakWeeksRequested = peakWeeksRequested; }

	public Integer getTaperWeeksRequested() { return taperWeeksRequested; }
	public void setTaperWeeksRequested(Integer taperWeeksRequested) { this.taperWeeksRequested = taperWeeksRequested; }

	public Activity getActivity() { return activity; }
	public void setActivity(Activity activity) { this.activity = activity; }

	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }

	public String getResultsUrl() { return resultsUrl; }
	public void setResultsUrl(String resultsUrl) { this.resultsUrl = resultsUrl; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }

	public Tag getKeySessionTag() { return keySessionTag; }
	public void setKeySessionTag(Tag keySessionTag) { this.keySessionTag = keySessionTag; }

	public boolean isPublic() { return isPublic; }
	public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
}
