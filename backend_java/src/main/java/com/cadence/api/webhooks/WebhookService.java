package com.cadence.api.webhooks;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

	private final WebhookRepository webhookRepository;
	private final WebhookSigner webhookSigner;

	public WebhookService(WebhookRepository webhookRepository, WebhookSigner webhookSigner) {
		this.webhookRepository = webhookRepository;
		this.webhookSigner = webhookSigner;
	}

	public List<Webhook> listForOwner(String ownerId) {
		return webhookRepository.findByOwnerIdOrderByCreatedDesc(ownerId);
	}

	@Transactional
	public Webhook create(User owner, String url, List<String> events) {
		List<String> unknown = events.stream().filter(e -> !WebhookEvent.ALL.contains(e)).toList();
		if (!unknown.isEmpty()) {
			throw new ValidationException("Unknown event(s): " + String.join(", ", unknown) + ".", "events");
		}
		Webhook webhook = new Webhook();
		webhook.setOwner(owner);
		webhook.setUrl(url);
		webhook.setEvents(events);
		webhook.setSecret(webhookSigner.generateSecret());
		webhook.setStatus(WebhookStatus.ACTIVE);
		return webhookRepository.save(webhook);
	}

	@Transactional
	public void delete(String id, String ownerId) {
		Webhook webhook = webhookRepository.findByIdAndOwnerId(id, ownerId)
				.orElseThrow(() -> new NotFoundException("No such webhook."));
		webhookRepository.delete(webhook);
	}
}
