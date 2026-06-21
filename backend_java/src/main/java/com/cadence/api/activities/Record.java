package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The 1 Hz stream. See {@link RecordId} for why there's no surrogate id - this is the TimescaleDB hypertable. */
@Entity
@Table(name = "record")
public class Record {

	@EmbeddedId
	private RecordId id;

	@MapsId("activityId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(nullable = false)
	private int t;

	private Integer power;
	private Integer heartrate;
	private Integer cadence;
	private Double altitude;
	private Double lat;
	private Double lng;
	private Double speed;

	@Column(name = "distance_km")
	private Double distanceKm;

	/** Stryd footpod developer fields - FIT-only, null from GPX/TCX. */
	@Column(name = "air_temp")
	private Double airTemp;

	private Integer humidity;

	/** CORE body-temperature sensor developer fields - FIT-only, null from GPX/TCX. */
	@Column(name = "core_temp")
	private Double coreTemp;

	@Column(name = "skin_temp")
	private Double skinTemp;

	@Column(name = "heat_strain")
	private Double heatStrain;

	public RecordId getId() {
		return id;
	}

	public void setId(RecordId id) {
		this.id = id;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
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

	public Double getAirTemp() {
		return airTemp;
	}

	public void setAirTemp(Double airTemp) {
		this.airTemp = airTemp;
	}

	public Integer getHumidity() {
		return humidity;
	}

	public void setHumidity(Integer humidity) {
		this.humidity = humidity;
	}

	public Double getCoreTemp() {
		return coreTemp;
	}

	public void setCoreTemp(Double coreTemp) {
		this.coreTemp = coreTemp;
	}

	public Double getSkinTemp() {
		return skinTemp;
	}

	public void setSkinTemp(Double skinTemp) {
		this.skinTemp = skinTemp;
	}

	public Double getHeatStrain() {
		return heatStrain;
	}

	public void setHeatStrain(Double heatStrain) {
		this.heatStrain = heatStrain;
	}
}
