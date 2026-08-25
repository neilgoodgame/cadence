package com.cadence.api.sharing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateVirtualCoachRequest(@NotBlank String name, @NotEmpty List<String> scopes) {
}
