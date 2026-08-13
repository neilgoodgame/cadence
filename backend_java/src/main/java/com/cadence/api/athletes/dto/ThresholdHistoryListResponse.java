package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.ThresholdField;
import java.util.List;

public record ThresholdHistoryListResponse(ThresholdField field, List<ThresholdHistoryEntryResponse> data) {
}
