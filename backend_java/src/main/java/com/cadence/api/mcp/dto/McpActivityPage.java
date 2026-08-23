package com.cadence.api.mcp.dto;

import java.util.List;

/** Same shape as {@code CursorPage} minus the REST-only {@code object: "list"} marker. */
public record McpActivityPage(boolean hasMore, String nextCursor, List<McpActivitySummary> data) {
}
