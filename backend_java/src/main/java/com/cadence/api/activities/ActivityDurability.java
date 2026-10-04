package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * One row per (activity, threshold, window_s) where a qualifying effort exists - best mean-max
 * power for {@code windowS} that <em>starts</em> after {@code threshold} of accumulated fatigue
 * (cycling: kJ of work; running: minutes of moving time) has been reached. See
 * ActivityDecouplingCalculator#computeDurabilityRows. The {@code threshold = 0} row is just the
 * activity's normal mean-max best (no fatigue gate), which keeps a "fresh" baseline in the same
 * query as every fatigued one.
 *
 * <p>Written idempotently (delete + reinsert per activity), not incrementally updated - see
 * computeDurabilityRows' own Javadoc.
 */
@Entity
@Table(name = "activity_durability")
public class ActivityDurability {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	/** Denormalised from activity.athlete/activity.startDate - queried directly by (athlete, sport, date range). */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private Sport sport;

	/** "kj" (ride) or "minutes" (run). */
	@Column(nullable = false)
	private String basis;

	/** 0 (fresh), 1000/2000/3000 (kJ, basis="kj") or 0/60/90 (minutes, basis="minutes"). */
	@Column(nullable = false)
	private int threshold;

	@Column(name = "window_s", nullable = false)
	private int windowS;

	@Column(nullable = false)
	private int power;

	@Column(name = "start_offset_s", nullable = false)
	private int startOffsetS;

	@Column(name = "activity_date", nullable = false)
	private LocalDate activityDate;

	public Long getId() {
		return id;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
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

	public String getBasis() {
		return basis;
	}

	public void setBasis(String basis) {
		this.basis = basis;
	}

	public int getThreshold() {
		return threshold;
	}

	public void setThreshold(int threshold) {
		this.threshold = threshold;
	}

	public int getWindowS() {
		return windowS;
	}

	public void setWindowS(int windowS) {
		this.windowS = windowS;
	}

	public int getPower() {
		return power;
	}

	public void setPower(int power) {
		this.power = power;
	}

	public int getStartOffsetS() {
		return startOffsetS;
	}

	public void setStartOffsetS(int startOffsetS) {
		this.startOffsetS = startOffsetS;
	}

	public LocalDate getActivityDate() {
		return activityDate;
	}

	public void setActivityDate(LocalDate activityDate) {
		this.activityDate = activityDate;
	}
}
