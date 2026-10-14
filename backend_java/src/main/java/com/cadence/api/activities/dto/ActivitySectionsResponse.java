package com.cadence.api.activities.dto;

import com.cadence.api.courses.Climb;
import com.cadence.api.courses.CourseSections;
import java.util.List;

/** GET /v1/activities/{id}/sections - an activity's flat / climb / descent sections, calculated on
 * read from its stored elevation series. */
public record ActivitySectionsResponse(
		String object,
		String activityId,
		String elevationQuality,
		List<double[]> elevationProfile,
		List<CourseSections.Section> sections,
		List<Climb> climbs) {}
