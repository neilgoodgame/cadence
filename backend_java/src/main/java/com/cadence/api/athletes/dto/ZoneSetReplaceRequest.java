package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.Zone;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ZoneSetReplaceRequest(@NotEmpty List<Zone> zones) {
}
