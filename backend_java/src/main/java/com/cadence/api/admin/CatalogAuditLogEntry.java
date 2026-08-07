package com.cadence.api.admin;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row per shoe-catalog add/remove made from the Admin screen. Scoped to catalog
 * changes only - user/admin-flag toggles and coach-grant revokes don't write here. */
@Entity
@Table(name = "catalog_audit_log_entry")
public class CatalogAuditLogEntry extends PrefixedIdEntity {

	@Column(nullable = false)
	private String description;

	@Column(nullable = false)
	private CatalogAuditAction action;

	// SET_NULL, not CASCADE, so the log entry outlives whichever admin made the change -
	// matching ShoeModel.createdBy's precedent for "who did this" fields.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "by_id")
	private User by;

	@Column(nullable = false)
	private Instant created;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "cal";
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public CatalogAuditAction getAction() {
		return action;
	}

	public void setAction(CatalogAuditAction action) {
		this.action = action;
	}

	public User getBy() {
		return by;
	}

	public void setBy(User by) {
		this.by = by;
	}

	public Instant getCreated() {
		return created;
	}
}
