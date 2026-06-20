package com.cadence.api.common.paging;

import java.util.List;

/** The {@code {"data": [...]}} envelope used by every list endpoint that isn't cursor-paginated. */
public record DataListResponse<T>(List<T> data) {
}
