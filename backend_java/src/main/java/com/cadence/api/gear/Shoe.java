package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "shoe")
public class Shoe extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shoe_model_version_id", nullable = false)
	private ShoeModelVersion shoeModelVersion;

	@Column(nullable = false)
	private String colourway = "";

	@Column(nullable = false)
	private String name;

	private String image;

	@Column(nullable = false)
	private String role = "";

	@Column(nullable = false)
	private int km;

	@Column(name = "limit_km", nullable = false)
	private int limitKm;

	@Column(nullable = false)
	private LocalDate since;

	@Column(nullable = false)
	private boolean retired = false;

	@PrePersist
	private void onCreate() {
		if (since == null) {
			since = LocalDate.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "shoe";
	}

	public User getAthlete() {
		return athlete;
	}

	public void setAthlete(User athlete) {
		this.athlete = athlete;
	}

	public ShoeModelVersion getShoeModelVersion() {
		return shoeModelVersion;
	}

	public void setShoeModelVersion(ShoeModelVersion shoeModelVersion) {
		this.shoeModelVersion = shoeModelVersion;
	}

	public String getColourway() {
		return colourway;
	}

	public void setColourway(String colourway) {
		this.colourway = colourway;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getImage() {
		return image;
	}

	public void setImage(String image) {
		this.image = image;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
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

	public LocalDate getSince() {
		return since;
	}

	public boolean isRetired() {
		return retired;
	}

	public void setRetired(boolean retired) {
		this.retired = retired;
	}
}
