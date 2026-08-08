package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkoutAutoMatchServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutAutoMatchService autoMatchService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private ScheduledWorkoutRepository scheduledWorkoutRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private TagRepository tagRepository;

	private User newAthlete(String email, boolean renameMatched, boolean appendDate) {
		return newAthlete(email, renameMatched, appendDate, false);
	}

	private User newAthlete(String email, boolean renameMatched, boolean appendDate, boolean copyTags) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete " + email);
		user.setPassword("irrelevant-for-this-test");
		user.setRenameMatchedActivities(renameMatched);
		user.setAppendMatchDateToName(appendDate);
		user.setCopyMatchedWorkoutTags(copyTags);
		return userRepository.save(user);
	}

	private Workout newWorkout(User athlete, String name) {
		return newWorkout(athlete, name, List.of());
	}

	private Workout newWorkout(User athlete, String name, List<String> tags) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName(name);
		workout.setSport(Sport.RUN);
		workout.setTags(tags);
		return workoutRepository.save(workout);
	}

	private Set<String> activityTagNames(Activity activity) {
		return Set.copyOf(activityTagRepository.findTagNamesByActivityId(activity.getId()));
	}

	private void schedule(User athlete, Workout workout, LocalDate date) {
		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(date);
		scheduled.setStatus(ScheduledWorkoutStatus.PLANNED);
		scheduledWorkoutRepository.save(scheduled);
	}

	private Activity newActivity(User athlete, String name, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName(name);
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	@Test
	void leavesNameUntouchedByDefault() {
		User athlete = newAthlete("wm-default@example.cc", false, false);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 13));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-13T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Morning run");
		assertThat(reloaded.getWorkout().getId()).isEqualTo(workout.getId());
	}

	@Test
	void renamesToWorkoutNameWhenPreferenceEnabled() {
		User athlete = newAthlete("wm-rename@example.cc", true, false);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 14));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-14T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Tempo run");
	}

	@Test
	void appendsDateOnlyWhenBothPreferencesEnabled() {
		User athlete = newAthlete("wm-append@example.cc", true, true);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 15));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-15T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Tempo run - 2026-06-15");
	}

	@Test
	void appendDatePreferenceHasNoEffectWhenRenameIsOff() {
		User athlete = newAthlete("wm-append-only@example.cc", false, true);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 16));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-16T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Morning run");
	}

	@Test
	void doesNotMatchDifferentSport() {
		User athlete = newAthlete("wm-sport@example.cc", true, false);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 17));
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Easy ride");
		activity.setStartDate(Instant.parse("2026-06-17T06:30:00Z"));
		activityRepository.save(activity);

		autoMatchService.attemptMatch(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Easy ride");
		assertThat(reloaded.getWorkout()).isNull();
	}

	@Test
	void doesNotCopyWorkoutTagsByDefault() {
		User athlete = newAthlete("wm-tags-default@example.cc", false, false);
		Workout workout = newWorkout(athlete, "Tempo run", List.of("Speedwork", "Race prep"));
		schedule(athlete, workout, LocalDate.of(2026, 6, 18));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-18T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		assertThat(activityTagNames(activity)).doesNotContain("Speedwork", "Race prep");
	}

	@Test
	void copiesWorkoutTagsWhenPreferenceEnabled() {
		User athlete = newAthlete("wm-tags-enabled@example.cc", false, false, true);
		Workout workout = newWorkout(athlete, "Tempo run", List.of("Speedwork", "Race prep"));
		schedule(athlete, workout, LocalDate.of(2026, 6, 19));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-19T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		assertThat(activityTagNames(activity)).isEqualTo(Set.of("Auto-matched", "Speedwork", "Race prep"));
		assertThat(tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Speedwork").orElseThrow().getOrigin())
				.isEqualTo(TagOrigin.AUTO);
	}

	@Test
	void reusesAnExistingTagWithTheSameNameInsteadOfDuplicating() {
		User athlete = newAthlete("wm-tags-reuse@example.cc", false, false, true);
		Tag existing = new Tag();
		existing.setAthlete(athlete);
		existing.setName("Speedwork");
		existing.setOrigin(TagOrigin.MANUAL);
		existing = tagRepository.save(existing);
		Workout workout = newWorkout(athlete, "Tempo run", List.of("Speedwork"));
		schedule(athlete, workout, LocalDate.of(2026, 6, 20));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-20T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		List<Tag> tags = tagRepository.findByAthleteIdOrderByName(athlete.getId()).stream()
				.filter(tag -> tag.getName().equalsIgnoreCase("Speedwork"))
				.toList();
		assertThat(tags).hasSize(1);
		Tag reloaded = tagRepository.findById(existing.getId()).orElseThrow();
		assertThat(reloaded.getOrigin()).isEqualTo(TagOrigin.MANUAL);
		assertThat(activityTagRepository.existsByActivityIdAndTagId(activity.getId(), existing.getId())).isTrue();
	}

	@Test
	void handlesWorkoutWithNoTagsGracefully() {
		User athlete = newAthlete("wm-tags-none@example.cc", false, false, true);
		Workout workout = newWorkout(athlete, "Tempo run");
		schedule(athlete, workout, LocalDate.of(2026, 6, 21));
		Activity activity = newActivity(athlete, "Morning run", Instant.parse("2026-06-21T06:30:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		assertThat(activityTagNames(activity)).isEqualTo(Set.of("Auto-matched"));
	}
}
