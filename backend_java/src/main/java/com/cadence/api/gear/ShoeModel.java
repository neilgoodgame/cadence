package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Catalog entry - a manufacturer + product line. Not athlete-owned. */
@Entity
@Table(name = "shoe_model")
public class ShoeModel extends PrefixedIdEntity {

	@Column(nullable = false)
	private String manufacturer;

	@Column(nullable = false)
	private String model;

	@Override
	protected String idPrefix() {
		return "sm";
	}

	public String getManufacturer() {
		return manufacturer;
	}

	public void setManufacturer(String manufacturer) {
		this.manufacturer = manufacturer;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}
}
