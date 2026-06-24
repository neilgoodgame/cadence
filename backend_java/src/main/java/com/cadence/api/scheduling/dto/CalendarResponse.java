package com.cadence.api.scheduling.dto;

import com.cadence.api.activities.dto.ActivityResponse;
import java.util.List;

/** data only ever contains ScheduledWorkout rows - unplannedActivities covers completed
 * activities in range that were never scheduled or matched to a designed workout. */
public record CalendarResponse(List<ScheduledWorkoutResponse> data, List<ActivityResponse> unplannedActivities) {
}
