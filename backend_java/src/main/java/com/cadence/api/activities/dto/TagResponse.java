package com.cadence.api.activities.dto;

import com.cadence.api.activities.TagOrigin;

public record TagResponse(String id, String name, TagOrigin origin, String color) {
}
