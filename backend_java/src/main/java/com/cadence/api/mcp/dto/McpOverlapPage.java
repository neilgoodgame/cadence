package com.cadence.api.mcp.dto;

import com.cadence.api.activities.dto.OverlapPairResponse;
import java.util.List;

/** Same shape as {@code CursorPage} minus the REST-only {@code object: "list"} marker.
 * {@link OverlapPairResponse} is already compact (unlike {@link McpActivitySummary}'s reason for
 * existing), so this just carries it directly rather than trimming further. */
public record McpOverlapPage(boolean hasMore, String nextCursor, List<OverlapPairResponse> data) {
}
