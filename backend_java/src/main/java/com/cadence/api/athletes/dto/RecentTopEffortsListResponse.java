package com.cadence.api.athletes.dto;

import java.util.List;

public record RecentTopEffortsListResponse(String since, List<RecentTopEffortResponse> data) {
}
