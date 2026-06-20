package com.cadence.api.athletes.dto;

import com.cadence.api.activities.BestEffortKind;
import java.util.List;

public record BestEffortListResponse(BestEffortKind kind, String period, List<BestEffortResponse> data) {
}
