package com.cadence.api.users;

import com.cadence.api.common.id.PrefixedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A one-time-use token proving control of the email address on a password-based account
 * (see EmailVerificationService). Stored hashed, same scheme as
 * {@link com.cadence.api.security.pat.PersonalAccessToken} - only the raw value mailed to
 * the athlete can complete verification, never anything recoverable from the row itself.
 */
@Entity
@Table(name = "email_verification_token")
public class EmailVerificationToken extends PrefixedIdEntity {

	@ManyToOne
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "hashed_secret", nullable = false)
	private String hashedSecret;

	@Column(nullable = false)
	private Instant created;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "evt";
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public String getHashedSecret() {
		return hashedSecret;
	}

	public void setHashedSecret(String hashedSecret) {
		this.hashedSecret = hashedSecret;
	}

	public Instant getCreated() {
		return created;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public void setUsedAt(Instant usedAt) {
		this.usedAt = usedAt;
	}

	public boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}
}
