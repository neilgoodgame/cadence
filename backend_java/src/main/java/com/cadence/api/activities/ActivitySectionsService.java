package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivitySectionsResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.courses.CourseService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity sections - the course sections pipeline (CourseSections) applied to a run, walk or
 * ride's recorded altitude. Mirrors the Python backend's activities/elevation.py.
 */
@Service
public class ActivitySectionsService {

	/** Sports whose elevation is worth splitting into flat / climb / descent sections. */
	public static final Set<Sport> SECTION_SPORTS = Set.of(Sport.RUN, Sport.WALK, Sport.BIKE);

	private final RecordRepository recordRepository;
	private final ActivityElevationRepository elevationRepository;
	private final CourseService courseService;

	public ActivitySectionsService(RecordRepository recordRepository,
			ActivityElevationRepository elevationRepository, CourseService courseService) {
		this.recordRepository = recordRepository;
		this.elevationRepository = elevationRepository;
		this.courseService = courseService;
	}

	public void requireSectionSport(Activity activity) {
		if (!SECTION_SPORTS.contains(activity.getSport())) {
			throw new UnprocessableEntityException("Sections are only calculated for runs, walks and rides.");
		}
	}

	/** (Re)builds and saves the activity's 10 m elevation series from its records' distance and
	 * altitude, skipping samples where distance didn't advance (paused). */
	@Transactional
	public ActivityElevation rebuildElevation(Activity activity) {
		List<Double> distances = new ArrayList<>();
		List<Double> altitudes = new ArrayList<>();
		Double last = null;
		for (RecordRepository.DistanceAltitude row : recordRepository.findDistanceAltitudeByActivityIdOrderByT(activity.getId())) {
			if (last != null && row.getDistanceKm() <= last) continue;
			distances.add(row.getDistanceKm());
			altitudes.add(row.getAltitude());
			last = row.getDistanceKm();
		}
		CourseSections.ElevationSeries series = CourseSections.buildElevationSeries(distances, altitudes);
		ActivityElevation elevation = elevationRepository.findById(activity.getId())
				.orElseGet(() -> new ActivityElevation(activity.getId()));
		elevation.setElevationQuality(series.elevationQuality());
		elevation.setElevationSeries(new ArrayList<>(series.series()));
		return elevationRepository.save(elevation);
	}

	/** The stored series, built on first read. */
	@Transactional
	public ActivityElevation ensureElevation(Activity activity) {
		return elevationRepository.findById(activity.getId()).orElseGet(() -> rebuildElevation(activity));
	}

	/** settings: a preview's overrides, or null for the owner's saved settings. */
	public ActivitySectionsResponse toResponse(Activity activity, ActivityElevation elevation, CourseSections.Settings settings) {
		CourseSections.Settings effective = settings != null ? settings : courseService.settingsFor(activity.getAthlete().getId());
		boolean ok = CourseSections.ELEVATION_QUALITY_OK.equals(elevation.getElevationQuality());
		List<CourseSections.Section> sections = ok
				? CourseSections.sectionsFromSeries(elevation.getElevationSeries(), effective)
				: List.of();
		return new ActivitySectionsResponse("activity_sections", activity.getId(), elevation.getElevationQuality(),
				ok ? CourseSections.profileFromSeries(elevation.getElevationSeries(), effective) : List.of(),
				sections, CourseSections.climbsFromSections(sections));
	}
}
