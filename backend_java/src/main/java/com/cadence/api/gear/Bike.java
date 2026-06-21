package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "bike")
public class Bike extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private String name;

	private BikeKind kind;

	@Column(nullable = false)
	private String groupset = "";

	@Column(name = "distance_km", nullable = false)
	private int distanceKm;

	// Not settable via the API yet - placeholders for future auto-computation from activities.
	@Column(nullable = false)
	private double hours;

	@Column(nullable = false)
	private int rides;

	@Override
	protected String idPrefix() {
		return "bike";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public BikeKind getKind() {
		return kind;
	}

	public void setKind(BikeKind kind) {
		this.kind = kind;
	}

	public String getGroupset() {
		return groupset;
	}

	public void setGroupset(String groupset) {
		this.groupset = groupset;
	}

	public int getDistanceKm() {
		return distanceKm;
	}

	public void setDistanceKm(int distanceKm) {
		this.distanceKm = distanceKm;
	}

	public double getHours() {
		return hours;
	}

	public int getRides() {
		return rides;
	}
}
