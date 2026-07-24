package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

	@Column(name = "distance_km")
	private Double distanceKm;

	/** Seconds. */
	@Column(name = "goal_time")
	private Integer goalTime;

	/** Seconds. */
	@Column(name = "result_time")
	private Integer resultTime;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "activity_id")
	private Activity activity;

	private String url;

	@Column(name = "results_url")
	private String resultsUrl;

	@Column(nullable = false)
	private String notes = "";

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

	public Double getDistanceKm() { return distanceKm; }
	public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }

	public Integer getGoalTime() { return goalTime; }
	public void setGoalTime(Integer goalTime) { this.goalTime = goalTime; }

	public Integer getResultTime() { return resultTime; }
	public void setResultTime(Integer resultTime) { this.resultTime = resultTime; }

	public Activity getActivity() { return activity; }
	public void setActivity(Activity activity) { this.activity = activity; }

	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }

	public String getResultsUrl() { return resultsUrl; }
	public void setResultsUrl(String resultsUrl) { this.resultsUrl = resultsUrl; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }
}
