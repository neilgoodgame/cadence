package com.cadence.api.mcp.tools.courses;

import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseService;
import com.cadence.api.mcp.dispatch.McpScopes;
import com.cadence.api.mcp.dispatch.McpToolAuthorizer;
import com.cadence.api.security.AccessGuard;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Calls the exact same {@link CourseService} the REST {@code CourseController} does. */
@Component
public class CourseTools {

	private final CourseService courseService;
	private final AccessGuard accessGuard;
	private final McpToolAuthorizer authorizer;

	public CourseTools(CourseService courseService, AccessGuard accessGuard, McpToolAuthorizer authorizer) {
		this.courseService = courseService;
		this.accessGuard = accessGuard;
		this.authorizer = authorizer;
	}

	@McpTool(name = "list_courses", description = "List the authenticated athlete's saved courses - "
			+ "name, location, distance, elevation gain and how many races have been run on each.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public List<Map<String, Object>> listCourses() {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);
		return courseService.list(athleteId, null).stream()
				.map(c -> {
					Map<String, Object> m = new java.util.LinkedHashMap<>();
					m.put("id", c.getId());
					m.put("name", c.getName());
					m.put("location_name", c.getLocationName());
					m.put("distance_km", c.getDistanceKm());
					m.put("elevation_gain_m", c.getElevationGainM());
					m.put("is_loop", c.isLoop());
					return m;
				})
				.toList();
	}

	@McpTool(name = "get_course", description = "Get one course's full detail - climbs, elevation "
			+ "profile summary, and the races run on it.",
			annotations = @McpTool.McpAnnotations(
					readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Map<String, Object> getCourse(String courseId) {
		authorizer.requireScope(McpScopes.ACTIVITIES_READ);
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireRead(athleteId);

		Course course;
		try {
			course = courseService.getCourse(courseId);
		}
		catch (com.cadence.api.common.error.NotFoundException e) {
			return Map.of("error", "not_found");
		}
		if (!course.getAthlete().getId().equals(athleteId)) {
			return Map.of("error", "not_found");
		}
		var response = courseService.toResponse(course);
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("id", response.id());
		m.put("name", response.name());
		m.put("location_name", response.locationName());
		m.put("distance_km", response.distanceKm());
		m.put("elevation_gain_m", response.elevationGainM());
		m.put("elevation_loss_m", response.elevationLossM());
		m.put("is_loop", response.isLoop());
		m.put("climbs", response.climbs());
		m.put("races", response.races());
		return m;
	}
}
