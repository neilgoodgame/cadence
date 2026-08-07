package com.cadence.api.users;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The athlete profile. There is no separate "Athlete" table - a single user
 * carries both their own training profile and (if {@link #isCoach}) the
 * ability to coach others via {@link com.cadence.api.sharing.UserRelationship}.
 */
@Entity
@Table(name = "users")
public class User extends PrefixedIdEntity {

	@Column(nullable = false)
	private String email;

	private String password;

	@Column(nullable = false)
	private String name;

	private String handle;

	private Integer age;

	@Column(name = "weight_kg")
	private Double weightKg;

	private Integer ftp;

	@Column(name = "critical_run_power")
	private Integer criticalRunPower;

	@Column(name = "threshold_pace", nullable = false)
	private String thresholdPace = "";

	private Integer lthr;

	@Column(name = "max_hr")
	private Integer maxHr;

	/** Optional - only used for the Karvonen heart-rate-reserve % shown on Activity Analysis's Stats tab. */
	@Column(name = "resting_hr")
	private Integer restingHr;

	@Column(name = "best_effort_top_n", nullable = false)
	private int bestEffortTopN = 10;

	@Column(name = "is_coach", nullable = false)
	private boolean coach = false;

	/** App-level admin flag (the in-app Admin screen) - distinct from any Django-side
	 * is_staff/is_superuser concept, which has no Java equivalent at all. */
	@Column(name = "is_admin", nullable = false)
	private boolean admin = false;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	// Auto-match naming preferences (WorkoutMatchTasklet) - both default off so existing
	// device-derived activity names are untouched unless opted in. appendMatchDateToName
	// only has an effect when renameMatchedActivities is also on.
	@Column(name = "rename_matched_activities", nullable = false)
	private boolean renameMatchedActivities = false;

	@Column(name = "append_match_date_to_name", nullable = false)
	private boolean appendMatchDateToName = false;

	/** Independent of the naming preferences above - copies the matched Workout's tags
	 * (Workout.tags, a plain list of names) onto the activity. */
	@Column(name = "copy_matched_workout_tags", nullable = false)
	private boolean copyMatchedWorkoutTags = false;

	@Column(name = "date_joined", nullable = false)
	private Instant dateJoined;

	@PrePersist
	private void onCreate() {
		if (dateJoined == null) {
			dateJoined = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "usr";
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getHandle() {
		return handle;
	}

	public void setHandle(String handle) {
		this.handle = handle;
	}

	public Integer getAge() {
		return age;
	}

	public void setAge(Integer age) {
		this.age = age;
	}

	public Double getWeightKg() {
		return weightKg;
	}

	public void setWeightKg(Double weightKg) {
		this.weightKg = weightKg;
	}

	public Integer getFtp() {
		return ftp;
	}

	public void setFtp(Integer ftp) {
		this.ftp = ftp;
	}

	public Integer getCriticalRunPower() {
		return criticalRunPower;
	}

	public void setCriticalRunPower(Integer criticalRunPower) {
		this.criticalRunPower = criticalRunPower;
	}

	public String getThresholdPace() {
		return thresholdPace;
	}

	public void setThresholdPace(String thresholdPace) {
		this.thresholdPace = thresholdPace;
	}

	public Integer getLthr() {
		return lthr;
	}

	public void setLthr(Integer lthr) {
		this.lthr = lthr;
	}

	public Integer getMaxHr() {
		return maxHr;
	}

	public void setMaxHr(Integer maxHr) {
		this.maxHr = maxHr;
	}

	public Integer getRestingHr() {
		return restingHr;
	}

	public void setRestingHr(Integer restingHr) {
		this.restingHr = restingHr;
	}

	public int getBestEffortTopN() {
		return bestEffortTopN;
	}

	public void setBestEffortTopN(int bestEffortTopN) {
		this.bestEffortTopN = bestEffortTopN;
	}

	public boolean isCoach() {
		return coach;
	}

	public void setCoach(boolean coach) {
		this.coach = coach;
	}

	public boolean isAdmin() {
		return admin;
	}

	public void setAdmin(boolean admin) {
		this.admin = admin;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public boolean isRenameMatchedActivities() {
		return renameMatchedActivities;
	}

	public void setRenameMatchedActivities(boolean renameMatchedActivities) {
		this.renameMatchedActivities = renameMatchedActivities;
	}

	public boolean isAppendMatchDateToName() {
		return appendMatchDateToName;
	}

	public void setAppendMatchDateToName(boolean appendMatchDateToName) {
		this.appendMatchDateToName = appendMatchDateToName;
	}

	public boolean isCopyMatchedWorkoutTags() {
		return copyMatchedWorkoutTags;
	}

	public void setCopyMatchedWorkoutTags(boolean copyMatchedWorkoutTags) {
		this.copyMatchedWorkoutTags = copyMatchedWorkoutTags;
	}

	public Instant getDateJoined() {
		return dateJoined;
	}
}
