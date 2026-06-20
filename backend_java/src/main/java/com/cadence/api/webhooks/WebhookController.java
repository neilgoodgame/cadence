package com.cadence.api.webhooks;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import com.cadence.api.webhooks.dto.WebhookCreateRequest;
import com.cadence.api.webhooks.dto.WebhookResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

	private final WebhookService webhookService;
	private final WebhookMapper webhookMapper;
	private final UserService userService;

	public WebhookController(WebhookService webhookService, WebhookMapper webhookMapper, UserService userService) {
		this.webhookService = webhookService;
		this.webhookMapper = webhookMapper;
		this.userService = userService;
	}

	@GetMapping("/v1/webhooks")
	public DataListResponse<WebhookResponse> listWebhooks() {
		String ownerId = AuthContextHolder.get().sub();
		var webhooks = webhookService.listForOwner(ownerId).stream().map(webhookMapper::toResponse).toList();
		return new DataListResponse<>(webhooks);
	}

	@PostMapping("/v1/webhooks")
	@ResponseStatus(HttpStatus.CREATED)
	public WebhookResponse createWebhook(@Valid @RequestBody WebhookCreateRequest request) {
		String ownerId = AuthContextHolder.get().sub();
		User owner = userService.getById(ownerId);
		Webhook webhook = webhookService.create(owner, request.url(), request.events());
		return webhookMapper.toCreatedResponse(webhook);
	}

	@DeleteMapping("/v1/webhooks/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteWebhook(@PathVariable String id) {
		String ownerId = AuthContextHolder.get().sub();
		webhookService.delete(id, ownerId);
	}
}
