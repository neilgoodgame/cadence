package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.StreamService;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.mcp.dto.McpStreamSummary;
import com.cadence.api.security.AccessGuard;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the same {@link StreamService} the REST {@code StreamController} does, but always at
 * {@code resolution=low} (1 sample per 15s - the service's own coarsest option) regardless of
 * what's asked, and returns {@link McpStreamSummary} rather than the raw
 * {@code StreamsResponse.fields} - never the full 1Hz per-sample series (a single hour-long
 * activity is 3600+ points per field). No resolution parameter is exposed for that reason.
 */
@Component
public class ActivityStreamTools {

	private final ActivityService activityService;
	private final StreamService streamService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityStreamTools(ActivityService activityService, StreamService streamService,
			AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.streamService = streamService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "get_activity_stream_summary", description = "Get a coarse (1 sample per 15s) "
			+ "time-series summary for an activity - min/max/avg per requested field plus the "
			+ "downsampled series itself. Useful for 'how variable was my power/HR' or 'show me "
			+ "the shape of this effort' - never the full per-second data.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public McpStreamSummary getActivityStreamSummary(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "Comma-separated fields, e.g. 'power,heartrate,cadence,altitude'. "
					+ "Omit for the activity's default set.", required = false) String fields) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireRead(activity.getAthlete().getId());
		var streams = streamService.getStreams(activity, fields, "low");
		return McpStreamSummary.from(streams.resolution(), streams.fields());
	}
}
