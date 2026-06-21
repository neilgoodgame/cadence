package com.cadence.api.uploads.batch;

import java.sql.Timestamp;

/**
 * A plain JavaBean (not a Java {@code record}) deliberately - {@code JdbcBatchItemWriter}'s
 * {@code BeanPropertyItemSqlParameterSourceProvider} resolves SQL parameters through
 * {@code getXxx()} accessors, which a Java record doesn't expose. {@code ts} is a
 * {@code java.sql.Timestamp} rather than {@code Instant}: this goes through plain JDBC
 * (bypassing Hibernate's type system, which does know how to bind an {@code Instant}), and
 * pgjdbc's own {@code setObject} can't infer a SQL type for a bare {@code Instant}.
 */
public class RecordRow {

	private String activityId;
	private Timestamp ts;
	private int t;
	private Integer power;
	private Integer heartrate;
	private Integer cadence;
	private Double altitude;
	private Double lat;
	private Double lng;
	private Double speed;
	private Double distanceKm;

	public String getActivityId() {
		return activityId;
	}

	public void setActivityId(String activityId) {
		this.activityId = activityId;
	}

	public Timestamp getTs() {
		return ts;
	}

	public void setTs(Timestamp ts) {
		this.ts = ts;
	}

	public int getT() {
		return t;
	}

	public void setT(int t) {
		this.t = t;
	}

	public Integer getPower() {
		return power;
	}

	public void setPower(Integer power) {
		this.power = power;
	}

	public Integer getHeartrate() {
		return heartrate;
	}

	public void setHeartrate(Integer heartrate) {
		this.heartrate = heartrate;
	}

	public Integer getCadence() {
		return cadence;
	}

	public void setCadence(Integer cadence) {
		this.cadence = cadence;
	}

	public Double getAltitude() {
		return altitude;
	}

	public void setAltitude(Double altitude) {
		this.altitude = altitude;
	}

	public Double getLat() {
		return lat;
	}

	public void setLat(Double lat) {
		this.lat = lat;
	}

	public Double getLng() {
		return lng;
	}

	public void setLng(Double lng) {
		this.lng = lng;
	}

	public Double getSpeed() {
		return speed;
	}

	public void setSpeed(Double speed) {
		this.speed = speed;
	}

	public Double getDistanceKm() {
		return distanceKm;
	}

	public void setDistanceKm(Double distanceKm) {
		this.distanceKm = distanceKm;
	}
}
