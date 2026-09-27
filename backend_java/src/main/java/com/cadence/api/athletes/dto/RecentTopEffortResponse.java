package com.cadence.api.athletes.dto;

import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.common.domain.Sport;
import java.time.LocalDate;
import java.util.Map;

/** One (activity, kind, window) entry for the Dashboard "Top efforts this week" card - see
 * {@code BestEffortController#recentTopEffortRanks}. {@code ranks} has one key per tracked
 * period ("16w"/"1y"/"all"), each either the entry's 1-based rank within that period's own
 * top-N leaderboard, or {@code null} if it didn't reach that period's top-N at all. */
public record RecentTopEffortResponse(String activityId, LocalDate date, Sport sport, BestEffortKind kind,
		String window, double value, String unit, Map<String, Integer> ranks) {
}
