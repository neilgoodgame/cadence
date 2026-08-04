package com.cadence.api.export.dto;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.LapResponse;
import com.cadence.api.activities.dto.StreamsResponse;
import java.util.List;

/** One activity's export entry: the same shapes the REST API already returns (ActivityResponse,
 * LapResponse, StreamsResponse), bundled together rather than flattened, so field ownership stays
 * unambiguous and adding this doesn't require a new merged DTO to keep in sync by hand. */
public record ActivityExportEntry(ActivityResponse activity, List<LapResponse> laps, StreamsResponse streams) {
}
