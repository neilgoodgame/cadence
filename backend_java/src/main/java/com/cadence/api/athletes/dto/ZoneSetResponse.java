package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.Zone;
import com.cadence.api.athletes.ZoneType;
import java.util.List;

public record ZoneSetResponse(ZoneType type, Double reference, List<Zone> zones) {
}
