package com.cadence.api.activities.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivityCommentCreateRequest(@NotBlank @Size(max = 4000) String text) {
}
