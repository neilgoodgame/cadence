package com.cadence.api.users;

import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.LapSource;
import com.cadence.api.athletes.RunningPowerSource;
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

	/** True immediately for social signups (the provider already verified the address) and
	 * once a password signup redeems its EmailVerificationToken - see EmailVerificationService. */
	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified = false;

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

	/** Rolling-window threshold determination (see com.cadence.api.athletes.ThresholdHistoryService):
	 * ftp/criticalRunPower/thresholdPace above are each the best qualifying effort within the
	 * trailing thresholdWindowDays - not a one-way ratchet, a value drops automatically once its
	 * source activity ages out of the window. 112 days = 16 weeks, matching this codebase's
	 * existing "16w" best-efforts period bucket. */
	@Column(name = "threshold_window_days", nullable = false)
	private int thresholdWindowDays = 112;

	/** A candidate activity whose implied value deviates from the athlete's then-current value by
	 * more than this percentage is treated as an outlier (e.g. corrupt power-meter data) and
	 * excluded from consideration. */
	@Column(name = "threshold_sanity_pct", nullable = false)
	private int thresholdSanityPct = 30;

	/** How many days before a threshold's current source activity ages out of thresholdWindowDays
	 * the Threshold suggestions feature warns the athlete (see
	 * ThresholdHistoryCalculator.warningLeadDays) - 0 = off. */
	@Column(name = "threshold_warning_days", nullable = false)
	private int thresholdWarningDays = 21;

	// Aerobic decoupling qualification thresholds (see DecouplingQualificationCalculator) - the
	// defaults match the design spec's own fixed limits. Read at compute time (ingest/
	// recompute), same "not retroactive until recomputed" convention as thresholdWindowDays/
	// thresholdSanityPct above - changing these doesn't repaint already-scored activities.
	@Column(name = "decoupling_vi_limit_bike", nullable = false)
	private double decouplingViLimitBike = 1.06;

	@Column(name = "decoupling_vi_limit_run", nullable = false)
	private double decouplingViLimitRun = 1.04;

	@Column(name = "decoupling_if_limit", nullable = false)
	private double decouplingIfLimit = 0.85;

	@Column(name = "decoupling_min_steady_minutes", nullable = false)
	private int decouplingMinSteadyMinutes = 60;

	// Heat-confound flags for the same decoupling card - two independent severity tiers, both
	// read from air/skin temp only (not core, which drifts up from sustained effort alone on
	// any long session regardless of weather). Warm is an OR (either signal elevated is enough
	// to caveat the reading); hot is an AND (both have to be elevated - a stricter bar).
	@Column(name = "decoupling_warm_air_temp", nullable = false)
	private double decouplingWarmAirTemp = 25.0;

	@Column(name = "decoupling_warm_skin_temp", nullable = false)
	private double decouplingWarmSkinTemp = 33.0;

	@Column(name = "decoupling_hot_air_temp", nullable = false)
	private double decouplingHotAirTemp = 30.0;

	@Column(name = "decoupling_hot_skin_temp", nullable = false)
	private double decouplingHotSkinTemp = 34.0;

	/** A running-power sample above this is treated as corrupt sensor data - not a real effort,
	 * a glitch - and dropped before it reaches best efforts, duration curves, normalized power, or
	 * threshold history. See RunningPowerSanitizer's Javadoc for the failure mode this guards
	 * against (third-party footpods, Stryd in particular, occasionally emit single-sample power
	 * readings in the thousands of watts with completely ordinary pace/cadence around them).
	 * Cycling is unaffected - its power comes from the FIT spec's native field, not this
	 * footpod-specific developer-field fallback. */
	@Column(name = "max_running_power_watts", nullable = false)
	private int maxRunningPowerWatts = 1000;

	/** How ThresholdHistoryCalculator derives an implied FTP from a bike activity - see
	 * FtpCalculationMethod's Javadoc for the tradeoff between its two values. */
	@Column(name = "ftp_calculation_method", nullable = false)
	private FtpCalculationMethod ftpCalculationMethod = FtpCalculationMethod.TWENTY_MIN_TEST;

	/** See RunningPowerSource's own Javadoc. Deliberately not a fallback preference - the
	 * non-selected source is completely ignored, not used when the selected one is momentarily
	 * missing. */
	@Column(name = "running_power_source", nullable = false)
	private RunningPowerSource runningPowerSource = RunningPowerSource.STRYD;

	@Column(name = "is_coach", nullable = false)
	private boolean coach = false;

	/** App-level admin flag (the in-app Admin screen) - distinct from any Django-side
	 * is_staff/is_superuser concept, which has no Java equivalent at all. */
	@Column(name = "is_admin", nullable = false)
	private boolean admin = false;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	/** Synthetic account with no real inbox and no password, created via the "virtual coach"
	 * flow (SharingService.createVirtualCoach) for an MCP client to authenticate as. Never
	 * logs into the web app - can only authenticate via the delegated personal access token
	 * minted alongside it. Restricted to exactly one coach relationship (see
	 * SharingService.createShare's guard); a real coach is not restricted this way. */
	@Column(name = "is_virtual", nullable = false)
	private boolean virtual = false;

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

	@Column(name = "lap_source", nullable = false)
	private LapSource lapSource = LapSource.MATCHED_WORKOUT;

	// Applied as a new Shoe's limitKm whenever one isn't given explicitly - both the single
	// "Add shoe" form (when its own field is left blank) and the gear CSV import (which has no
	// field for it at all, see ShoeImportRequest). Purely a default, not a constraint - an
	// individual shoe's limitKm can still be edited to anything afterwards.
	@Column(name = "default_shoe_limit_km", nullable = false)
	private int defaultShoeLimitKm = 800;

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

	public boolean isEmailVerified() {
		return emailVerified;
	}

	public void setEmailVerified(boolean emailVerified) {
		this.emailVerified = emailVerified;
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

	public int getThresholdWindowDays() {
		return thresholdWindowDays;
	}

	public void setThresholdWindowDays(int thresholdWindowDays) {
		this.thresholdWindowDays = thresholdWindowDays;
	}

	public int getThresholdSanityPct() {
		return thresholdSanityPct;
	}

	public void setThresholdSanityPct(int thresholdSanityPct) {
		this.thresholdSanityPct = thresholdSanityPct;
	}

	public int getThresholdWarningDays() {
		return thresholdWarningDays;
	}

	public void setThresholdWarningDays(int thresholdWarningDays) {
		this.thresholdWarningDays = thresholdWarningDays;
	}

	public double getDecouplingViLimitBike() {
		return decouplingViLimitBike;
	}

	public void setDecouplingViLimitBike(double decouplingViLimitBike) {
		this.decouplingViLimitBike = decouplingViLimitBike;
	}

	public double getDecouplingViLimitRun() {
		return decouplingViLimitRun;
	}

	public void setDecouplingViLimitRun(double decouplingViLimitRun) {
		this.decouplingViLimitRun = decouplingViLimitRun;
	}

	public double getDecouplingIfLimit() {
		return decouplingIfLimit;
	}

	public void setDecouplingIfLimit(double decouplingIfLimit) {
		this.decouplingIfLimit = decouplingIfLimit;
	}

	public int getDecouplingMinSteadyMinutes() {
		return decouplingMinSteadyMinutes;
	}

	public void setDecouplingMinSteadyMinutes(int decouplingMinSteadyMinutes) {
		this.decouplingMinSteadyMinutes = decouplingMinSteadyMinutes;
	}

	public double getDecouplingWarmAirTemp() {
		return decouplingWarmAirTemp;
	}

	public void setDecouplingWarmAirTemp(double decouplingWarmAirTemp) {
		this.decouplingWarmAirTemp = decouplingWarmAirTemp;
	}

	public double getDecouplingWarmSkinTemp() {
		return decouplingWarmSkinTemp;
	}

	public void setDecouplingWarmSkinTemp(double decouplingWarmSkinTemp) {
		this.decouplingWarmSkinTemp = decouplingWarmSkinTemp;
	}

	public double getDecouplingHotAirTemp() {
		return decouplingHotAirTemp;
	}

	public void setDecouplingHotAirTemp(double decouplingHotAirTemp) {
		this.decouplingHotAirTemp = decouplingHotAirTemp;
	}

	public double getDecouplingHotSkinTemp() {
		return decouplingHotSkinTemp;
	}

	public void setDecouplingHotSkinTemp(double decouplingHotSkinTemp) {
		this.decouplingHotSkinTemp = decouplingHotSkinTemp;
	}

	public int getMaxRunningPowerWatts() {
		return maxRunningPowerWatts;
	}

	public void setMaxRunningPowerWatts(int maxRunningPowerWatts) {
		this.maxRunningPowerWatts = maxRunningPowerWatts;
	}

	public FtpCalculationMethod getFtpCalculationMethod() {
		return ftpCalculationMethod;
	}

	public void setFtpCalculationMethod(FtpCalculationMethod ftpCalculationMethod) {
		this.ftpCalculationMethod = ftpCalculationMethod;
	}

	public RunningPowerSource getRunningPowerSource() {
		return runningPowerSource;
	}

	public void setRunningPowerSource(RunningPowerSource runningPowerSource) {
		this.runningPowerSource = runningPowerSource;
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

	public boolean isVirtual() {
		return virtual;
	}

	public void setVirtual(boolean virtual) {
		this.virtual = virtual;
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

	public LapSource getLapSource() {
		return lapSource;
	}

	public void setLapSource(LapSource lapSource) {
		this.lapSource = lapSource;
	}

	public int getDefaultShoeLimitKm() {
		return defaultShoeLimitKm;
	}

	public void setDefaultShoeLimitKm(int defaultShoeLimitKm) {
		this.defaultShoeLimitKm = defaultShoeLimitKm;
	}

	public Instant getDateJoined() {
		return dateJoined;
	}
}
