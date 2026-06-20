package com.cadence.api.gear;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "service_record")
public class ServiceRecord extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "component_id", nullable = false)
	private Component component;

	private ServiceAction action;

	@Column(nullable = false)
	private boolean reset = true;

	@Column(nullable = false)
	private String note = "";

	@Column(nullable = false)
	private LocalDate date;

	@Override
	protected String idPrefix() {
		return "svc";
	}

	public Component getComponent() {
		return component;
	}

	public void setComponent(Component component) {
		this.component = component;
	}

	public ServiceAction getAction() {
		return action;
	}

	public void setAction(ServiceAction action) {
		this.action = action;
	}

	public boolean isReset() {
		return reset;
	}

	public void setReset(boolean reset) {
		this.reset = reset;
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}
}
