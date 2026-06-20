package com.cadence.api.webhooks;

import com.cadence.api.security.PermissionService;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enqueues a delivery for every active subscription that wants {@code event} and can see
 * {@code athleteId}'s data. {@code data} is whatever response DTO the firing call site already
 * builds for its own API response - flattened to a plain map here so it composes with the
 * {@code payload} JSONB column without every call site needing to build that map itself.
 *
 * <p>Every caller of this class is a {@code @TransactionalEventListener(AFTER_COMMIT)} method,
 * which runs while Spring's transaction-synchronization bookkeeping for the just-finished
 * transaction is still technically "active" - so without {@code REQUIRES_NEW} here, the JPA
 * calls below try to join that already-completing transaction instead of opening a fresh one,
 * and fail with {@code TransactionRequiredException: No active transaction}.
 */
@Component
public class WebhookEventPublisher {

	private final WebhookRepository webhookRepository;
	private final WebhookDeliveryRepository webhookDeliveryRepository;
	private final PermissionService permissionService;
	private final WebhookDeliveryService webhookDeliveryService;
	private final JsonMapper jsonMapper;

	public WebhookEventPublisher(WebhookRepository webhookRepository, WebhookDeliveryRepository webhookDeliveryRepository,
			PermissionService permissionService, WebhookDeliveryService webhookDeliveryService, JsonMapper jsonMapper) {
		this.webhookRepository = webhookRepository;
		this.webhookDeliveryRepository = webhookDeliveryRepository;
		this.permissionService = permissionService;
		this.webhookDeliveryService = webhookDeliveryService;
		this.jsonMapper = jsonMapper;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fireEvent(String event, String athleteId, Object data) {
		Map<String, Object> dataMap = jsonMapper.convertValue(data, new TypeReference<Map<String, Object>>() {
		});
		for (Webhook webhook : webhookRepository.findActiveSubscribersTo(event)) {
			if (!permissionService.mayRead(webhook.getOwner().getId(), athleteId)) {
				continue;
			}
			WebhookDelivery delivery = new WebhookDelivery();
			delivery.setWebhook(webhook);
			delivery.setEvent(event);
			delivery.setPayload(Map.of("event", event, "created", Instant.now().toString(), "data", dataMap));
			// saveAndFlush, not save: this method has no surrounding transaction (it runs from an
			// AFTER_COMMIT listener), so without an explicit flush the IDENTITY-generated id isn't
			// guaranteed to be populated yet when read below.
			webhookDeliveryRepository.saveAndFlush(delivery);

			// @Async void: delivery problems never bubble back here, by construction - a bad
			// endpoint must not be able to fail the upload/scheduling action that fired this.
			webhookDeliveryService.deliver(delivery.getId());
		}
	}
}
