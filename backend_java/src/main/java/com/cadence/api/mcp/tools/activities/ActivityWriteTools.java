package com.cadence.api.mcp.tools.activities;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagMapper;
import com.cadence.api.activities.TagService;
import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.TagAttachResponse;
import com.cadence.api.activities.dto.TagResponse;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Calls the exact same {@link ActivityService}/{@link TagService} the REST
 * {@code ActivityController}/{@code TagController} do. {@code update_activity} is deliberately
 * scoped to renaming only, not the REST PATCH endpoint's full field set (sport, linked workout/
 * gear, weight/hydration/temperature overrides) - those are structural edits the web app's own
 * UI already handles carefully; a virtual coach's legitimate need here is renaming and tagging,
 * matching what a real user asked their own coach to do that had no tool to act on.
 */
@Component
public class ActivityWriteTools {

	private final ActivityService activityService;
	private final TagService tagService;
	private final TagMapper tagMapper;
	private final ActivityTagRepository activityTagRepository;
	private final UserService userService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public ActivityWriteTools(ActivityService activityService, TagService tagService, TagMapper tagMapper,
			ActivityTagRepository activityTagRepository, UserService userService, AccessGuard accessGuard,
			McpToolAuthorizer authorizer) {
		this.activityService = activityService;
		this.tagService = tagService;
		this.tagMapper = tagMapper;
		this.activityTagRepository = activityTagRepository;
		this.userService = userService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "update_activity", description = "Rename an activity (from list_activities/"
			+ "get_activity). Only the name can be changed through this tool - other fields "
			+ "(sport, linked workout/gear, weight/hydration/temperature overrides) require the "
			+ "web app.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ActivityResponse updateActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The new activity name", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		if (name == null || name.isBlank()) {
			throw new ValidationException("name must not be blank.", "name");
		}
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		Activity updated = activityService.updateActivity(activity, Map.of("name", name));
		return activityService.toResponse(updated);
	}

	@McpTool(name = "tag_activity", description = "Attach a tag to an activity (from "
			+ "list_activities/get_activity) by name - creates the tag if the athlete doesn't "
			+ "already have one with this name (case-insensitive), matching the web app's tag "
			+ "picker. A no-op if the activity already carries this tag.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public TagAttachResponse tagActivity(
			@McpToolParam(description = "The activity id, e.g. act_xxxxxxxxxxxx", required = true) String activityId,
			@McpToolParam(description = "The tag name, e.g. \"VO2 max\"", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		if (name == null || name.isBlank()) {
			throw new ValidationException("name must not be blank.", "name");
		}
		Activity activity = activityService.getActivity(activityId);
		accessGuard.requireWrite(activity.getAthlete().getId());
		User athlete = userService.getById(activity.getAthlete().getId());
		Tag tag = tagService.attachTag(activity, athlete, null, name);
		return new TagAttachResponse(activityId, tagMapper.toResponse(tag));
	}

	@McpTool(name = "rename_tag", description = "Renames a tag (see list_tags for exact names) "
			+ "to new_name. If the athlete already has a different tag with that name "
			+ "(case-insensitive), merges into it instead - every activity carrying the old tag "
			+ "ends up carrying the existing one, and the old tag is removed. Returns the tag "
			+ "that now holds new_name.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public TagResponse renameTag(
			@McpToolParam(description = "The tag's current name, e.g. \"VO2 max\"", required = true) String name,
			@McpToolParam(description = "The new name", required = true) String newName) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		Tag tag = tagService.findByName(athleteId, name);
		Tag result = tagService.renameTag(athleteId, tag.getId(), newName);
		return new TagResponse(result.getId(), result.getName(), result.getOrigin(), result.getColor(),
				activityTagRepository.countByTagId(result.getId()));
	}

	@McpTool(name = "delete_tag", description = "Deletes a tag (see list_tags for exact names) "
			+ "entirely, removing it from every activity that carries it - not just an unused one.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
	public Map<String, Object> deleteTag(
			@McpToolParam(description = "The tag's name, e.g. \"VO2 max\"", required = true) String name) {
		authorizer.requireScope(McpScopes.ACTIVITIES_WRITE);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		Tag tag = tagService.findByName(athleteId, name);
		String deletedName = tag.getName();
		tagService.deleteTag(athleteId, tag.getId());
		return Map.of("deleted", true, "name", deletedName);
	}
}
