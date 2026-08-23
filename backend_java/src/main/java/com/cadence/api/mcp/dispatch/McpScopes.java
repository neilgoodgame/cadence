package com.cadence.api.mcp.dispatch;

/**
 * The same six scope strings {@code FirstPartyClientConfig}/{@code McpClientConfig} already
 * use - not a new taxonomy. {@code activities:read} is reused as the umbrella "read my training
 * data" scope for every MCP read tool (workouts/calendar/gear/races included), since the
 * existing scope set has no per-domain read scopes - see the plan's "read-scope granularity"
 * open question.
 */
public final class McpScopes {

	public static final String ACTIVITIES_READ = "activities:read";
	public static final String ACTIVITIES_WRITE = "activities:write";
	public static final String WORKOUTS_WRITE = "workouts:write";
	public static final String CALENDAR_WRITE = "calendar:write";
	public static final String GEAR_WRITE = "gear:write";

	private McpScopes() {
	}
}
