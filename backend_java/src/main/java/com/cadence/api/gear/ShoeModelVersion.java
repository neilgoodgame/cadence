package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Catalog entry - a generation of a shoe model. Never deletable while a {@link Shoe} references it. */
@Entity
@Table(name = "shoe_model_version")
public class ShoeModelVersion extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shoe_model_id", nullable = false)
	private ShoeModel shoeModel;

	@Column(nullable = false)
	private String version;

	@Override
	protected String idPrefix() {
		return "smv";
	}

	public ShoeModel getShoeModel() {
		return shoeModel;
	}

	public void setShoeModel(ShoeModel shoeModel) {
		this.shoeModel = shoeModel;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}
}
