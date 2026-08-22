package com.cadence.api.workouts.dto;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.workouts.ChartPreviewPoint;
import java.time.Instant;
import java.util.List;

public record WorkoutResponse(
		String id,
		String name,
		Sport sport,
		String type,
		int duration,
		int tss,
		String folderId,
		List<String> tags,
		List<ChartPreviewPoint> chartPreview,
		Instant updatedAt) {
}
