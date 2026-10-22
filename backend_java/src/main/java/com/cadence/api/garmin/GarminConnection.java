package com.cadence.api.garmin;

import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "garmin_connection")
public class GarminConnection extends PrefixedIdEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false, unique = true)
	private User owner;

	@Column(name = "garmin_email", nullable = false)
	private String garminEmail;

	@Column(name = "api_key_encrypted", nullable = false)
	private String apiKeyEncrypted;

	@Column(name = "key_expires_on")
	private LocalDate keyExpiresOn;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Column(nullable = false)
	private Instant created;

	@Column(nullable = false)
	private Instant updated;

	@PrePersist
	private void onCreate() {
		Instant now = Instant.now();
		if (created == null) {
			created = now;
		}
		updated = now;
	}

	@PreUpdate
	private void onUpdate() {
		updated = Instant.now();
	}

	@Override
	protected String idPrefix() {
		return "gcn";
	}

	public User getOwner() {
		return owner;
	}

	public void setOwner(User owner) {
		this.owner = owner;
	}

	public String getGarminEmail() {
		return garminEmail;
	}

	public void setGarminEmail(String garminEmail) {
		this.garminEmail = garminEmail;
	}

	public String getApiKeyEncrypted() {
		return apiKeyEncrypted;
	}

	public void setApiKeyEncrypted(String apiKeyEncrypted) {
		this.apiKeyEncrypted = apiKeyEncrypted;
	}

	public LocalDate getKeyExpiresOn() {
		return keyExpiresOn;
	}

	public void setKeyExpiresOn(LocalDate keyExpiresOn) {
		this.keyExpiresOn = keyExpiresOn;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public void setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
	}

	public Instant getCreated() {
		return created;
	}

	public Instant getUpdated() {
		return updated;
	}
}
