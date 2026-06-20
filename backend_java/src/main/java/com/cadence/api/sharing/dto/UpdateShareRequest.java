package com.cadence.api.sharing.dto;

import com.cadence.api.sharing.ShareRole;
import jakarta.validation.constraints.NotNull;

public record UpdateShareRequest(@NotNull ShareRole role) {
}
