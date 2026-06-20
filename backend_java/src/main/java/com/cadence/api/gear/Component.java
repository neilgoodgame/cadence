package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "component")
public class Component extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "bike_id", nullable = false)
	private Bike bike;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int km;

	@Column(name = "limit_km", nullable = false)
	private int limitKm;

	@Column(nullable = false)
	private String model = "";

	@Override
	protected String idPrefix() {
		return "cmp";
	}

	public Bike getBike() {
		return bike;
	}

	public void setBike(Bike bike) {
		this.bike = bike;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getKm() {
		return km;
	}

	public void setKm(int km) {
		this.km = km;
	}

	public int getLimitKm() {
		return limitKm;
	}

	public void setLimitKm(int limitKm) {
		this.limitKm = limitKm;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}
}
