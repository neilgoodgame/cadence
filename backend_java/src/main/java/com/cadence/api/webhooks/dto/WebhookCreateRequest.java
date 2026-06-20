package com.cadence.api.webhooks.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record WebhookCreateRequest(@NotBlank String url, @NotEmpty List<String> events) {
}
