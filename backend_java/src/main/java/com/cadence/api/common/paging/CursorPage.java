package com.cadence.api.common.paging;

import java.util.List;

/** The {@code {"object":"list","has_more","next_cursor","data"}} envelope for cursor-paginated (seek-based) list endpoints. */
public record CursorPage<T>(boolean hasMore, String nextCursor, List<T> data) {

	public String object() {
		return "list";
	}
}
