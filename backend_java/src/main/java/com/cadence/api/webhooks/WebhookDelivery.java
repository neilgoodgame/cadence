package com.cadence.api.webhooks;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Internal delivery-attempt bookkeeping, not part of the public API surface. */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "webhook_id", nullable = false)
	private Webhook webhook;

	@Column(nullable = false)
	private String event;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> payload;

	@Column(nullable = false)
	private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error", nullable = false)
	private String lastError = "";

	@Column(nullable = false)
	private Instant created;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public Webhook getWebhook() {
		return webhook;
	}

	public void setWebhook(Webhook webhook) {
		this.webhook = webhook;
	}

	public String getEvent() {
		return event;
	}

	public void setEvent(String event) {
		this.event = event;
	}

	public Map<String, Object> getPayload() {
		return payload;
	}

	public void setPayload(Map<String, Object> payload) {
		this.payload = payload;
	}

	public WebhookDeliveryStatus getStatus() {
		return status;
	}

	public void setStatus(WebhookDeliveryStatus status) {
		this.status = status;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
	}

	public Instant getCreated() {
		return created;
	}
}
