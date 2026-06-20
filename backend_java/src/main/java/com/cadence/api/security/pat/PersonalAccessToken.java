package com.cadence.api.security.pat;

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
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "personal_access_token")
public class PersonalAccessToken extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String prefix;

	@Column(name = "hashed_secret", nullable = false)
	private String hashedSecret;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<String> scopes = List.of();

	@Column(nullable = false)
	private Instant created;

	@Column(name = "expires_at")
	private LocalDate expiresAt;

	@Column(name = "last_used")
	private LocalDate lastUsed;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "tok";
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	public String getHashedSecret() {
		return hashedSecret;
	}

	public void setHashedSecret(String hashedSecret) {
		this.hashedSecret = hashedSecret;
	}

	public List<String> getScopes() {
		return scopes;
	}

	public void setScopes(List<String> scopes) {
		this.scopes = scopes;
	}

	public Instant getCreated() {
		return created;
	}

	public LocalDate getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(LocalDate expiresAt) {
		this.expiresAt = expiresAt;
	}

	public LocalDate getLastUsed() {
		return lastUsed;
	}

	public void setLastUsed(LocalDate lastUsed) {
		this.lastUsed = lastUsed;
	}
}
