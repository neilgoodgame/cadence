package com.cadence.api.sharing.dto;

import com.cadence.api.sharing.ShareRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateShareRequest(@NotBlank String invitee, @NotNull ShareRole role) {
}
