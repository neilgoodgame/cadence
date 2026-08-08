package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkoutMatchServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutMatchService workoutMatchService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private TagRepository tagRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Workout newWorkout(User athlete, int duration, int tss) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("VO2 Max 5x5");
		workout.setSport(Sport.BIKE);
		workout.setDuration(duration);
		workout.setTss(tss);
		return workoutRepository.save(workout);
	}

	private Activity newActivity(User athlete, Workout workout, String name, int movingTime, double distanceKm,
			Integer avgPower, int tss) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setStartDate(Instant.now());
		activity.setMovingTime(movingTime);
		activity.setDistanceKm(distanceKm);
		activity.setAvgPower(avgPower);
		activity.setTss(tss);
		activity.setWorkout(workout);
		return activityRepository.save(activity);
	}

	private void markAutoMatched(User athlete, Activity activity) {
		Tag tag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Auto-matched").orElseGet(() -> {
			Tag created = new Tag();
			created.setAthlete(athlete);
			created.setName("Auto-matched");
			created.setOrigin(TagOrigin.AUTO);
			return tagRepository.save(created);
		});
		ActivityTag link = new ActivityTag();
		link.setActivity(activity);
		link.setTag(tag);
		activityTagRepository.save(link);
	}

	@Test
	void autoMatchIncludesConfidenceComplianceAndActivityStats() {
		User athlete = newAthlete("wm-auto@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity activity = newActivity(athlete, workout, "Auto Match", 1200, 35.0, 231, 33);
		markAutoMatched(athlete, activity);

		List<WorkoutMatchResponse> matches = workoutMatchService.listMatches(workout.getId(), "auto");

		assertThat(matches).hasSize(1);
		WorkoutMatchResponse match = matches.get(0);
		assertThat(match.activityId()).isEqualTo(activity.getId());
		assertThat(match.method()).isEqualTo("auto");
		assertThat(match.confidence()).isEqualTo(1.0);
		assertThat(match.compliance()).isEqualTo(1.0);
		assertThat(match.tss()).isEqualTo(33);
		assertThat(match.movingTime()).isEqualTo(1200);
		assertThat(match.distanceKm()).isEqualTo(35.0);
		assertThat(match.avgPower()).isEqualTo(231);
	}

	@Test
	void manualMatchHasNoConfidenceAndNullableAvgPower() {
		User athlete = newAthlete("wm-manual@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity activity = newActivity(athlete, workout, "Manual Match", 1000, 28.0, null, 20);

		List<WorkoutMatchResponse> matches = workoutMatchService.listMatches(workout.getId(), "manual");

		assertThat(matches).hasSize(1);
		WorkoutMatchResponse match = matches.get(0);
		assertThat(match.method()).isEqualTo("manual");
		assertThat(match.confidence()).isNull();
		assertThat(match.avgPower()).isNull();
	}

	@Test
	void listsAllMatchesByDefault() {
		User athlete = newAthlete("wm-all@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity auto = newActivity(athlete, workout, "Auto Match", 1200, 35.0, 231, 33);
		markAutoMatched(athlete, auto);
		Activity manual = newActivity(athlete, workout, "Manual Match", 1000, 28.0, null, 20);
		Activity unrelated = new Activity();
		unrelated.setAthlete(athlete);
		unrelated.setSport(Sport.BIKE);
		unrelated.setName("Unrelated");
		unrelated.setStartDate(Instant.now());
		activityRepository.save(unrelated);

		List<WorkoutMatchResponse> matches = workoutMatchService.listMatches(workout.getId(), "all");

		assertThat(matches).extracting(WorkoutMatchResponse::activityId)
				.containsExactlyInAnyOrder(auto.getId(), manual.getId());
	}
}
