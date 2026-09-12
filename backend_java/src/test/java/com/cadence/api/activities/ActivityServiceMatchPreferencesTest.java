package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.LapSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.TargetType;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutStep;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Accepting a Scan-for-matches candidate is ActivityService.updateActivity with just
 * workout_id in the body - covers it applying the same rename/copy-tags/regenerate-laps
 * preferences ingest-time auto-match already does (see WorkoutMatchPreferenceService and
 * WorkoutAutoMatchServiceIntegrationTest, which this mirrors). */
class ActivityServiceMatchPreferencesTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private WorkoutRepository workoutRepository;
	@Autowired
	private ActivityTagRepository activityTagRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private LapRepository lapRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name, Instant start) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(start);
		return activityRepository.save(activity);
	}

	private Workout newWorkout(User athlete, String name, List<String> tags) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName(name);
		workout.setSport(Sport.RUN);
		workout.setTags(tags);
		return workoutRepository.save(workout);
	}

	@Test
	void doesNotRenameByDefault() {
		User athlete = newAthlete("match-pref-rename-default@example.cc");
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of());

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getName()).isEqualTo("Morning Run");
	}

	@Test
	void renamesWhenPreferenceEnabled() {
		User athlete = newAthlete("match-pref-rename-on@example.cc");
		athlete.setRenameMatchedActivities(true);
		userRepository.save(athlete);
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of());

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getName()).isEqualTo("Tempo run");
	}

	@Test
	void prefersAnExplicitNameOverTheRenamePreference() {
		User athlete = newAthlete("match-pref-rename-explicit@example.cc");
		athlete.setRenameMatchedActivities(true);
		userRepository.save(athlete);
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of());

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId(), "name", "My own title"));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getName()).isEqualTo("My own title");
	}

	@Test
	void doesNotCopyTagsByDefault() {
		User athlete = newAthlete("match-pref-tags-default@example.cc");
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of("endurance"));

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		assertThat(activityTagRepository.findTagNamesByActivityId(activity.getId())).isEmpty();
	}

	@Test
	void copiesTagsWhenPreferenceEnabled() {
		User athlete = newAthlete("match-pref-tags-on@example.cc");
		athlete.setCopyMatchedWorkoutTags(true);
		userRepository.save(athlete);
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of("endurance"));

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		assertThat(activityTagRepository.findTagNamesByActivityId(activity.getId())).containsExactly("endurance");
	}

	@Test
	void regeneratesLapsWhenPreferenceEnabled() {
		User athlete = newAthlete("match-pref-laps-on@example.cc");
		athlete.setLapSource(LapSource.MATCHED_WORKOUT);
		userRepository.save(athlete);
		Instant start = Instant.parse("2026-01-01T07:00:00Z");
		Activity activity = newActivity(athlete, "Morning Run", start);
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Tempo run");
		workout.setSport(Sport.RUN);
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(0);
		step.setKind(StepKind.BLOCK);
		step.setEndType(StepEndType.TIME);
		step.setDuration(300);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(100.0);
		step.setTargetHigh(100.0);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);
		for (int t = 0; t <= 300; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			recordRepository.save(record);
		}

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).getWorkoutStep().getId()).isEqualTo(step.getId());
	}

	@Test
	void leavesLapsUntouchedByDefault() {
		User athlete = newAthlete("match-pref-laps-default@example.cc");
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Lap existing = new Lap();
		existing.setActivity(activity);
		existing.setIndex(1);
		existing.setDuration(1800);
		existing.setDistanceKm(5.0);
		lapRepository.save(existing);
		Workout workout = newWorkout(athlete, "Tempo run", List.of());

		activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).getWorkoutStep()).isNull();
	}

	@Test
	void resubmittingTheSameWorkoutIdDoesNotReapplyPreferences() {
		User athlete = newAthlete("match-pref-resubmit@example.cc");
		athlete.setRenameMatchedActivities(true);
		userRepository.save(athlete);
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		Workout workout = newWorkout(athlete, "Tempo run", List.of());
		activity = activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));
		activity = activityService.updateActivity(activity, Map.of("name", "Renamed by me"));

		// Re-submitting the same workout_id (e.g. an unrelated form resave) must not clobber the
		// name the athlete set afterwards - it isn't a new match.
		Activity result = activityService.updateActivity(activity, Map.of("workout_id", workout.getId()));

		assertThat(result.getName()).isEqualTo("Renamed by me");
	}

	@Test
	void unlinkingAWorkoutDoesNotApplyMatchPreferences() {
		User athlete = newAthlete("match-pref-unlink@example.cc");
		athlete.setCopyMatchedWorkoutTags(true);
		userRepository.save(athlete);
		Workout workout = newWorkout(athlete, "Tempo run", List.of("endurance"));
		Activity activity = newActivity(athlete, "Morning Run", Instant.parse("2026-01-01T07:00:00Z"));
		activity.setWorkout(workout);
		activityRepository.save(activity);

		activityService.updateActivity(activity, Collections.singletonMap("workout_id", null));

		assertThat(activityTagRepository.findTagNamesByActivityId(activity.getId())).isEmpty();
	}
}
