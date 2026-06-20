package com.cadence.api.sharing;

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

/**
 * A grant from one user (owner) to another (grantee). The same row powers both
 * "share my data with someone" and "be coached by someone" - {@code viewer} is
 * read-only, {@code coach} additionally allows scheduling/assigning workouts.
 */
@Entity
@Table(name = "user_relationship")
public class UserRelationship extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "grantee_id", nullable = false)
	private User grantee;

	@Column(nullable = false)
	private ShareRole role;

	@Column(nullable = false)
	private ShareStatus status = ShareStatus.PENDING;

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
		return "rel";
	}

	public User getOwner() {
		return owner;
	}

	public void setOwner(User owner) {
		this.owner = owner;
	}

	public User getGrantee() {
		return grantee;
	}

	public void setGrantee(User grantee) {
		this.grantee = grantee;
	}

	public ShareRole getRole() {
		return role;
	}

	public void setRole(ShareRole role) {
		this.role = role;
	}

	public ShareStatus getStatus() {
		return status;
	}

	public void setStatus(ShareStatus status) {
		this.status = status;
	}

	public Instant getCreated() {
		return created;
	}
}
