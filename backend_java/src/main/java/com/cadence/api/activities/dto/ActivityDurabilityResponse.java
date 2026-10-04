package com.cadence.api.activities.dto;

public record ActivityDurabilityResponse(int threshold, int windowS, int power, int startOffsetS) {
}
