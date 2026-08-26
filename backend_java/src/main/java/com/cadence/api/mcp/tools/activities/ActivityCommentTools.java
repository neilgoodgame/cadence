package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityCommentService;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.dto.ActivityCommentResponse;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link ActivityCommentService} the REST {@code ActivityCommentController}
 * does - including its read-gated (not write-gated) permission check, matching that a comment is
 * "a lightweight social feature, not a data mutation" (see {@code ActivityComment}'s Javadoc):
 * anyone who can see the activity - athlete, coach, or viewer share - can comment on it. Gated
 * behind the {@code activities:write} MCP scope regardless, since granting a token the ability to
 * post *something* is still a deliberate write-capability choice for whoever creates that token,
 * separate from the underlying per-resource read/write check.
 */
@Component
public class ActivityCommentTools {

	private final ActivityCommentService activityCommentService;
	private final ActivityService activityService;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityCommentTools(ActivityCommentService activityCommentService, ActivityService activityService,
			UserService userService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.activityCommentService = activityCommentService;
		this.activityService = activityService;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "post_activity_comment", description = "Post a short comment on an activity "
			+ "(from list_activities/get_activity) - visible to the athlete and anyone else with "
			+ "access to it, e.g. coaching feedback on a specific session.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
	public ActivityCommentResponse postActivityComment(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The comment text, up to 4000 characters", required = true) String text) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		// The REST endpoint's request DTO enforces this via @NotBlank/@Size - a tool parameter
		// has no such bean-validated wrapper, so it's checked by hand here.
		if (text == null || text.isBlank()) {
			throw new ValidationException("text must not be blank.", "text");
		}
		if (text.length() > 4000) {
			throw new ValidationException("text must be 4000 characters or fewer.", "text");
		}
		Activity activity = activityService.getActivity(activityId);
		String sub = accessGuard.requireRead(activity.getAthlete().getId());
		User author = userService.getById(sub);
		return activityCommentService.create(activity, author, text);
	}

	@McpTool(name = "list_activity_comments", description = "List the comments on an activity "
			+ "(from list_activities/get_activity), oldest first - who wrote each one and their "
			+ "role (athlete/coach/viewer). Use this to see whether the athlete replied to a "
			+ "comment before posting another.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<ActivityCommentResponse> listActivityComments(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireRead(activity.getAthlete().getId());
		return activityCommentService.list(activityId);
	}
}
