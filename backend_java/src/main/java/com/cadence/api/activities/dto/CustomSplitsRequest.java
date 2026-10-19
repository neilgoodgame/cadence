package com.cadence.api.activities.dto;

import java.util.List;

/** POST /v1/activities/{id}/splits and /v1/races/{id}/splits request body. */
public record CustomSplitsRequest(List<CustomSplitStepRequest> steps) {}
