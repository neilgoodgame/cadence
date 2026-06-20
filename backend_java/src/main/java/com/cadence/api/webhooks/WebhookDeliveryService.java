package com.cadence.api.webhooks;

import com.cadence.api.common.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * One delivery attempt per call. {@code @Retryable} + {@code @Async} is the idiomatic Spring
 * Retry combination for "retry a slow I/O call without blocking the caller" - 6 attempts total
 * (1 initial + 5 retries) with exponential backoff capped at 600s, the same envelope as the
 * Python backend's Celery {@code autoretry_for}/{@code retry_backoff_max=600} config. Each
 * attempt's outcome is persisted via a plain repository {@code save()} (which commits in its
 * own transaction) before the method either returns or rethrows - so a delivery's state is
 * visible between retries regardless of how the attempt ends, the same property the Python
 * version gets from Django auto-committing each {@code .save()} outside of an atomic block.
 */
@Service
public class WebhookDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

	private final WebhookDeliveryRepository webhookDeliveryRepository;
	private final WebhookSigner webhookSigner;
	private final RestClient webhookRestClient;
	private final JsonMapper jsonMapper;

	public WebhookDeliveryService(WebhookDeliveryRepository webhookDeliveryRepository, WebhookSigner webhookSigner,
			RestClient webhookRestClient, JsonMapper jsonMapper) {
		this.webhookDeliveryRepository = webhookDeliveryRepository;
		this.webhookSigner = webhookSigner;
		this.webhookRestClient = webhookRestClient;
		this.jsonMapper = jsonMapper;
	}

	@Async
	@Retryable(retryFor = RestClientException.class, maxAttempts = 6, backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 600_000))
	public void deliver(Long deliveryId) {
		WebhookDelivery delivery = webhookDeliveryRepository.findByIdWithWebhook(deliveryId)
				.orElseThrow(() -> new NotFoundException("No such webhook delivery."));
		Webhook webhook = delivery.getWebhook();
		byte[] rawBody = jsonMapper.writeValueAsBytes(delivery.getPayload());
		delivery.setAttempts(delivery.getAttempts() + 1);

		try {
			webhookRestClient.post()
					.uri(webhook.getUrl())
					.header("Content-Type", "application/json")
					.header("X-Cadence-Signature", webhookSigner.sign(webhook.getSecret(), rawBody))
					.header("X-Cadence-Event", delivery.getEvent())
					.header("X-Cadence-Delivery", String.valueOf(delivery.getId()))
					.body(rawBody)
					.retrieve()
					.toBodilessEntity();
			delivery.setStatus(WebhookDeliveryStatus.SUCCEEDED);
			delivery.setLastError("");
			webhookDeliveryRepository.save(delivery);
		}
		catch (RestClientException e) {
			delivery.setLastError(e.getMessage());
			delivery.setStatus(WebhookDeliveryStatus.PENDING);
			webhookDeliveryRepository.save(delivery);
			throw e;
		}
	}

	@Recover
	public void recover(RestClientException e, Long deliveryId) {
		log.warn("Webhook delivery {} exhausted all retry attempts: {}", deliveryId, e.getMessage());
		webhookDeliveryRepository.findById(deliveryId).ifPresent(delivery -> {
			delivery.setStatus(WebhookDeliveryStatus.FAILED);
			delivery.setLastError(e.getMessage());
			webhookDeliveryRepository.save(delivery);
		});
	}
}
