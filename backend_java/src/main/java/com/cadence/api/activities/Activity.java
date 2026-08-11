package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.Shoe;
import com.cadence.api.users.User;
import com.cadence.api.workouts.Workout;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "activity")
public class Activity extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private Sport sport;

	@Column(nullable = false)
	private Environment environment = Environment.OUTDOOR;

	@Column(name = "has_gps", nullable = false)
	private boolean hasGps;

	@Column(nullable = false)
	private String name;

	@Column(name = "start_date", nullable = false)
	private Instant startDate;

	@Column(nullable = false)
	private String source = "";

	// Recording device from the file's metadata (FIT file_id), e.g. "Zwift" or
	// "Garmin Epix Gen2". Empty when the format doesn't carry it (GPX/TCX).
	@Column(nullable = false)
	private String device = "";

	@Column(name = "moving_time", nullable = false)
	private int movingTime;

	@Column(name = "distance_km", nullable = false)
	private double distanceKm;

	@Column(name = "distance_source", nullable = false)
	private DistanceSource distanceSource = DistanceSource.GPS;

	@Column(name = "avg_power")
	private Integer avgPower;

	@Column(name = "norm_power")
	private Integer normPower;

	private Double intensity;

	@Column(nullable = false)
	private int tss;

	@Column(name = "avg_hr")
	private Integer avgHr;

	@Column(name = "max_hr")
	private Integer maxHr;

	private Integer ascent;

	/**
	 * Extended stats (Activity Analysis "Stats" tab). All computed once at ingest from the
	 * record stream, same pattern as avgPower/maxHr above - null whenever the source data
	 * needed for that one metric wasn't present. Matches the Python backend's
	 * 0011_activity_avg_cadence_activity_avg_left_balance_pct_and_more migration.
	 */
	@Column(name = "max_power")
	private Integer maxPower;

	@Column(name = "avg_cadence")
	private Integer avgCadence;

	@Column(name = "max_cadence")
	private Integer maxCadence;

	/** km/h. */
	@Column(name = "max_speed")
	private Double maxSpeed;

	/** Metres. */
	@Column(name = "total_descent")
	private Integer totalDescent;

	/** Metres. */
	@Column(name = "elevation_min")
	private Integer elevationMin;

	/** Metres. */
	@Column(name = "elevation_max")
	private Integer elevationMax;

	/** Power-based estimate only - see {@link com.cadence.api.uploads.UploadCalculations#caloriesFromWorkKj}. */
	@Column(name = "calories")
	private Integer calories;

	/** Edwards' TRIMP: sum over HR zones of (minutes in zone * zone number 1-5). */
	@Column(name = "trimp")
	private Double trimp;

	/** % of power from the left leg (dual-sided/balance-capable power meters only). Right % = 100 - this. */
	@Column(name = "avg_left_balance_pct")
	private Double avgLeftBalancePct;

	/** The athlete's threshold(s) at the moment this activity was created (upload or import) -
	 * not stored to be edited, just so zones/TSS stay historically accurate instead of moving
	 * every time the athlete's current profile changes (see ZoneService.referenceFor). Only the
	 * field(s) relevant to this activity's own sport are ever set (bike -> ftpSnapshot, run ->
	 * the other two); the other(s) stay null regardless of sport. */
	@Column(name = "ftp_snapshot")
	private Integer ftpSnapshot;

	@Column(name = "critical_run_power_snapshot")
	private Integer criticalRunPowerSnapshot;

	@Column(name = "threshold_pace_snapshot")
	private String thresholdPaceSnapshot = "";

	/** Set when this activity's own best effort implies a higher threshold than what's on
	 * record (see ThresholdDetectionService) - cleared (not just recorded elsewhere) once the
	 * athlete accepts or dismisses it, so this field doubles as "is there a pending suggestion." */
	@Column(name = "suggested_ftp")
	private Integer suggestedFtp;

	@Column(name = "suggested_critical_run_power")
	private Integer suggestedCriticalRunPower;

	@Column(name = "suggested_threshold_pace")
	private String suggestedThresholdPace = "";

	@Column(name = "start_weight_kg")
	private Double startWeightKg;

	@Column(name = "end_weight_kg")
	private Double endWeightKg;

	@Column(name = "fluids_ml")
	private Integer fluidsMl;

	/** Stryd-derived (run only) or manually set via PATCH for activities with no sensor data - see ActivityService.updateActivity. */
	@Column(name = "avg_air_temp")
	private Double avgAirTemp;

	@Column(name = "avg_humidity")
	private Integer avgHumidity;

	/** Garmin's Firstbeat-derived training load, from a FIT session message (no GPX/TCX equivalent). Device-computed, never user-settable. */
	@Column(name = "aerobic_training_effect")
	private Double aerobicTrainingEffect;

	@Column(name = "anaerobic_training_effect")
	private Double anaerobicTrainingEffect;

	@Column(name = "training_effect_label", nullable = false)
	private String trainingEffectLabel = "";

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "workout_id")
	private Workout workout;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "bike_id")
	private Bike bike;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "shoe_id")
	private Shoe shoe;

	/** Set on the per-leg children of a multisport activity; null everywhere else. DB cascades child deletion. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_activity_id")
	private Activity parentActivity;

	/**
	 * Set on a duplicate recording of another activity (the "primary"); null everywhere else.
	 * Only the primary counts toward training load. DB sets this null when the primary is
	 * deleted - a duplicate is a full activity, not a dependent like a multisport leg.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "primary_activity_id")
	private Activity primaryActivity;

	@Override
	protected String idPrefix() {
		return "act";
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

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public boolean isHasGps() {
		return hasGps;
	}

	public void setHasGps(boolean hasGps) {
		this.hasGps = hasGps;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Instant getStartDate() {
		return startDate;
	}

	public void setStartDate(Instant startDate) {
		this.startDate = startDate;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public int getMovingTime() {
		return movingTime;
	}

	public void setMovingTime(int movingTime) {
		this.movingTime = movingTime;
	}

	public double getDistanceKm() {
		return distanceKm;
	}

	public void setDistanceKm(double distanceKm) {
		this.distanceKm = distanceKm;
	}

	public DistanceSource getDistanceSource() {
		return distanceSource;
	}

	public void setDistanceSource(DistanceSource distanceSource) {
		this.distanceSource = distanceSource;
	}

	public Integer getAvgPower() {
		return avgPower;
	}

	public void setAvgPower(Integer avgPower) {
		this.avgPower = avgPower;
	}

	public Integer getNormPower() {
		return normPower;
	}

	public void setNormPower(Integer normPower) {
		this.normPower = normPower;
	}

	public Double getIntensity() {
		return intensity;
	}

	public void setIntensity(Double intensity) {
		this.intensity = intensity;
	}

	public int getTss() {
		return tss;
	}

	public void setTss(int tss) {
		this.tss = tss;
	}

	public Integer getAvgHr() {
		return avgHr;
	}

	public void setAvgHr(Integer avgHr) {
		this.avgHr = avgHr;
	}

	public Integer getMaxHr() {
		return maxHr;
	}

	public void setMaxHr(Integer maxHr) {
		this.maxHr = maxHr;
	}

	public Integer getAscent() {
		return ascent;
	}

	public void setAscent(Integer ascent) {
		this.ascent = ascent;
	}

	public Integer getMaxPower() {
		return maxPower;
	}

	public void setMaxPower(Integer maxPower) {
		this.maxPower = maxPower;
	}

	public Integer getAvgCadence() {
		return avgCadence;
	}

	public void setAvgCadence(Integer avgCadence) {
		this.avgCadence = avgCadence;
	}

	public Integer getMaxCadence() {
		return maxCadence;
	}

	public void setMaxCadence(Integer maxCadence) {
		this.maxCadence = maxCadence;
	}

	public Double getMaxSpeed() {
		return maxSpeed;
	}

	public void setMaxSpeed(Double maxSpeed) {
		this.maxSpeed = maxSpeed;
	}

	public Integer getTotalDescent() {
		return totalDescent;
	}

	public void setTotalDescent(Integer totalDescent) {
		this.totalDescent = totalDescent;
	}

	public Integer getElevationMin() {
		return elevationMin;
	}

	public void setElevationMin(Integer elevationMin) {
		this.elevationMin = elevationMin;
	}

	public Integer getElevationMax() {
		return elevationMax;
	}

	public void setElevationMax(Integer elevationMax) {
		this.elevationMax = elevationMax;
	}

	public Integer getCalories() {
		return calories;
	}

	public void setCalories(Integer calories) {
		this.calories = calories;
	}

	public Double getTrimp() {
		return trimp;
	}

	public void setTrimp(Double trimp) {
		this.trimp = trimp;
	}

	public Double getAvgLeftBalancePct() {
		return avgLeftBalancePct;
	}

	public void setAvgLeftBalancePct(Double avgLeftBalancePct) {
		this.avgLeftBalancePct = avgLeftBalancePct;
	}

	public Integer getFtpSnapshot() {
		return ftpSnapshot;
	}

	public void setFtpSnapshot(Integer ftpSnapshot) {
		this.ftpSnapshot = ftpSnapshot;
	}

	public Integer getCriticalRunPowerSnapshot() {
		return criticalRunPowerSnapshot;
	}

	public void setCriticalRunPowerSnapshot(Integer criticalRunPowerSnapshot) {
		this.criticalRunPowerSnapshot = criticalRunPowerSnapshot;
	}

	public String getThresholdPaceSnapshot() {
		return thresholdPaceSnapshot;
	}

	public void setThresholdPaceSnapshot(String thresholdPaceSnapshot) {
		this.thresholdPaceSnapshot = thresholdPaceSnapshot;
	}

	public Integer getSuggestedFtp() {
		return suggestedFtp;
	}

	public void setSuggestedFtp(Integer suggestedFtp) {
		this.suggestedFtp = suggestedFtp;
	}

	public Integer getSuggestedCriticalRunPower() {
		return suggestedCriticalRunPower;
	}

	public void setSuggestedCriticalRunPower(Integer suggestedCriticalRunPower) {
		this.suggestedCriticalRunPower = suggestedCriticalRunPower;
	}

	public String getSuggestedThresholdPace() {
		return suggestedThresholdPace;
	}

	public void setSuggestedThresholdPace(String suggestedThresholdPace) {
		this.suggestedThresholdPace = suggestedThresholdPace;
	}

	public Double getStartWeightKg() {
		return startWeightKg;
	}

	public void setStartWeightKg(Double startWeightKg) {
		this.startWeightKg = startWeightKg;
	}

	public Double getEndWeightKg() {
		return endWeightKg;
	}

	public void setEndWeightKg(Double endWeightKg) {
		this.endWeightKg = endWeightKg;
	}

	public Integer getFluidsMl() {
		return fluidsMl;
	}

	public void setFluidsMl(Integer fluidsMl) {
		this.fluidsMl = fluidsMl;
	}

	public Double getAvgAirTemp() {
		return avgAirTemp;
	}

	public void setAvgAirTemp(Double avgAirTemp) {
		this.avgAirTemp = avgAirTemp;
	}

	public Integer getAvgHumidity() {
		return avgHumidity;
	}

	public void setAvgHumidity(Integer avgHumidity) {
		this.avgHumidity = avgHumidity;
	}

	public Double getAerobicTrainingEffect() {
		return aerobicTrainingEffect;
	}

	public void setAerobicTrainingEffect(Double aerobicTrainingEffect) {
		this.aerobicTrainingEffect = aerobicTrainingEffect;
	}

	public Double getAnaerobicTrainingEffect() {
		return anaerobicTrainingEffect;
	}

	public void setAnaerobicTrainingEffect(Double anaerobicTrainingEffect) {
		this.anaerobicTrainingEffect = anaerobicTrainingEffect;
	}

	public String getTrainingEffectLabel() {
		return trainingEffectLabel;
	}

	public void setTrainingEffectLabel(String trainingEffectLabel) {
		this.trainingEffectLabel = trainingEffectLabel;
	}

	public Workout getWorkout() {
		return workout;
	}

	public void setWorkout(Workout workout) {
		this.workout = workout;
	}

	public Bike getBike() {
		return bike;
	}

	public void setBike(Bike bike) {
		this.bike = bike;
	}

	public Shoe getShoe() {
		return shoe;
	}

	public void setShoe(Shoe shoe) {
		this.shoe = shoe;
	}

	public Activity getParentActivity() {
		return parentActivity;
	}

	public void setParentActivity(Activity parentActivity) {
		this.parentActivity = parentActivity;
	}

	public Activity getPrimaryActivity() {
		return primaryActivity;
	}

	public void setPrimaryActivity(Activity primaryActivity) {
		this.primaryActivity = primaryActivity;
	}
}
