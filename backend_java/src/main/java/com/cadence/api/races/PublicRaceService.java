package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.courses.CourseService;
import com.cadence.api.courses.dto.CourseResponse;
import com.cadence.api.races.dto.PublicRaceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The unauthenticated /v1/public/races/{id} reads - see PublicRaceResponse for what's exposed. */
@Service
public class PublicRaceService {

	private final RaceRepository raceRepository;
	private final CourseService courseService;

	public PublicRaceService(RaceRepository raceRepository, CourseService courseService) {
		this.raceRepository = raceRepository;
		this.courseService = courseService;
	}

	/** A not-public race is indistinguishable from a missing one - no existence leak to an
	 * unauthenticated caller probing ids. */
	public Race getPublicRace(String id) {
		Race race = raceRepository.findById(id).orElseThrow(() -> new NotFoundException("No such race."));
		if (!race.isPublic()) {
			throw new NotFoundException("No such race.");
		}
		return race;
	}

	/** Writable (not readOnly) because ensureElevationSeries can build a course's elevation series
	 * on its first read, which writes. Transactional also because it dereferences the race's lazy
	 * athlete/activity/course associations, and open-in-view is off. */
	@Transactional
	public PublicRaceResponse toPublicResponse(String id) {
		Race race = getPublicRace(id);
		Activity activity = race.getActivity();
		PublicRaceResponse.ActivityStats stats = activity == null ? null
				: new PublicRaceResponse.ActivityStats(activity.getAvgHr(), activity.getAvgPower(), activity.getAvgAirTemp(),
						activity.getAvgHumidity());
		PublicRaceResponse.Course course = null;
		if (race.getCourse() != null) {
			CourseResponse full = courseService.toResponse(courseService.ensureElevationSeries(race.getCourse()), null);
			course = new PublicRaceResponse.Course(full.name(), full.locationName(), full.distanceKm(), full.elevationGainM(),
					full.elevationLossM(), full.elevationMaxM(), full.elevationMinM(), full.isLoop(), full.routePolyline(),
					full.elevationProfile(), full.elevationQuality(), full.sections());
		}
		return new PublicRaceResponse(race.getId(), race.getAthlete().getName(), race.getName(), race.getDate().toString(),
				race.getSport() != null ? race.getSport().wireValue() : "", race.getDistanceKm(),
				RaceService.formatDuration(race.getGoalTime()), RaceService.formatDuration(race.getResultTime()),
				race.getUrl(), race.getResultsUrl(), stats, course);
	}
}
