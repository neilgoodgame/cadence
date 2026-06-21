package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ZoneType;

public record ZoneSetReplaceResponse(ZoneType type, Double reference, boolean updated) {
}
