package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.athletes.LapSource;
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

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private LapRepository lapRepository;

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

	/** A single flat 300s block - just enough structure to exercise the lap_source gate. */
	private Workout newSingleStepWorkout(User athlete) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Tempo run");
		workout.setSport(Sport.RUN);
		workout = workoutRepository.save(workout);

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
		return workoutRepository.save(workout);
	}

	private void seedRecords(Activity activity, Instant start, int totalSeconds) {
		for (int t = 0; t <= totalSeconds; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			recordRepository.save(record);
		}
	}

	/** Two distinct constant-power phases - enough variance in the expected curve for a
	 * meaningful (non-undefined) correlation, unlike a single flat step. */
	private Workout newTwoPhaseWorkout(User athlete, String name, int phaseSeconds) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName(name);
		workout.setSport(Sport.RUN);
		workout.setDuration(phaseSeconds * 2);
		workout = workoutRepository.save(workout);

		WorkoutStep low = new WorkoutStep();
		low.setWorkout(workout);
		low.setOrder(0);
		low.setKind(StepKind.BLOCK);
		low.setEndType(StepEndType.TIME);
		low.setDuration(phaseSeconds);
		low.setTargetType(TargetType.POWER);
		low.setTargetLow(60.0);
		low.setTargetHigh(60.0);
		workout.getSteps().add(low);

		WorkoutStep high = new WorkoutStep();
		high.setWorkout(workout);
		high.setOrder(1);
		high.setKind(StepKind.BLOCK);
		high.setEndType(StepEndType.TIME);
		high.setDuration(phaseSeconds);
		high.setTargetType(TargetType.POWER);
		high.setTargetLow(110.0);
		high.setTargetHigh(110.0);
		workout.getSteps().add(high);

		return workoutRepository.save(workout);
	}

	private void seedTwoPhaseRecords(Activity activity, Instant start, int phaseSeconds) {
		for (int t = 0; t <= phaseSeconds * 2; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(t < phaseSeconds ? 150 : 280);
			recordRepository.save(record);
		}
	}

	@Test
	void ambiguousSameDayCandidatesResolvedByCorrelation() {
		// Regression coverage for a real bug found live: two same-day, same-sport, still-planned
		// candidates had no tie-break at all - whichever the DB happened to return first won,
		// regardless of which one the activity actually matched. The correlating candidate must
		// win even though findMatchCandidates orders by an opaque id, not by which one is right.
		User athlete = newAthlete("wm-ambiguous-resolved@example.cc", false, false);
		Workout matchingWorkout = newTwoPhaseWorkout(athlete, "Matching workout", 1800);
		Workout wrongWorkout = newWorkout(athlete, "Steady run");
		WorkoutStep flatStep = new WorkoutStep();
		flatStep.setWorkout(wrongWorkout);
		flatStep.setOrder(0);
		flatStep.setKind(StepKind.BLOCK);
		flatStep.setEndType(StepEndType.TIME);
		flatStep.setDuration(3600);
		flatStep.setTargetType(TargetType.POWER);
		flatStep.setTargetLow(90.0);
		flatStep.setTargetHigh(90.0);
		wrongWorkout.getSteps().add(flatStep);
		wrongWorkout.setDuration(3600);
		wrongWorkout = workoutRepository.save(wrongWorkout);
		String matchingWorkoutId = matchingWorkout.getId();
		String wrongWorkoutId = wrongWorkout.getId();
		schedule(athlete, matchingWorkout, LocalDate.of(2026, 6, 24));
		schedule(athlete, wrongWorkout, LocalDate.of(2026, 6, 24));
		Instant start = Instant.parse("2026-06-24T12:00:00Z");
		Activity activity = newActivity(athlete, "Run", start);
		activity.setMovingTime(3600);
		activity = activityRepository.save(activity);
		seedTwoPhaseRecords(activity, start, 1800);

		autoMatchService.attemptMatch(activity.getId());

		Activity reloadedActivity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloadedActivity.getWorkout().getId()).isEqualTo(matchingWorkoutId);
		List<ScheduledWorkout> scheduled = scheduledWorkoutRepository.findByAthleteIdOrderByDate(athlete.getId());
		ScheduledWorkout matchedSlot =
				scheduled.stream().filter(s -> s.getWorkout().getId().equals(matchingWorkoutId)).findFirst().orElseThrow();
		ScheduledWorkout unmatchedSlot =
				scheduled.stream().filter(s -> s.getWorkout().getId().equals(wrongWorkoutId)).findFirst().orElseThrow();
		assertThat(matchedSlot.getStatus()).isEqualTo(ScheduledWorkoutStatus.COMPLETED);
		assertThat(matchedSlot.getActivity().getId()).isEqualTo(activity.getId());
		assertThat(unmatchedSlot.getStatus()).isEqualTo(ScheduledWorkoutStatus.PLANNED);
		assertThat(unmatchedSlot.getActivity()).isNull();
	}

	@Test
	void ambiguousSameDayCandidatesWithNoCorrelationSignalLeftUnmatched() {
		// When correlation can't disambiguate at all (here: neither candidate workout has any
		// steps, so both fail scannabilityErrorFor), the fix is to leave both candidates planned
		// for a human to resolve - not to silently guess, which is exactly how the real bug
		// above went unnoticed (the wrong guess still gets tagged "Auto-matched" and looks
		// resolved).
		User athlete = newAthlete("wm-ambiguous-unresolved@example.cc", false, false);
		Workout workoutA = newWorkout(athlete, "Workout A");
		Workout workoutB = newWorkout(athlete, "Workout B");
		schedule(athlete, workoutA, LocalDate.of(2026, 6, 25));
		schedule(athlete, workoutB, LocalDate.of(2026, 6, 25));
		Activity activity = newActivity(athlete, "Run", Instant.parse("2026-06-25T06:00:00Z"));

		autoMatchService.attemptMatch(activity.getId());

		Activity reloadedActivity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloadedActivity.getWorkout()).isNull();
		List<ScheduledWorkout> scheduled = scheduledWorkoutRepository.findByAthleteIdOrderByDate(athlete.getId());
		assertThat(scheduled).allSatisfy(s -> {
			assertThat(s.getStatus()).isEqualTo(ScheduledWorkoutStatus.PLANNED);
			assertThat(s.getActivity()).isNull();
		});
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
		// Regression coverage for a real bug found live: copied workout tags (ordinary
		// descriptive content like "road"/"marathon", not a system marker) were created as
		// AUTO, which TagService.detachTag permanently refuses to remove from any activity -
		// silently breaking tag removal in the UI for every activity that ever reused that tag
		// name. Only the "Auto-matched" marker tag itself should stay AUTO.
		assertThat(tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Speedwork").orElseThrow().getOrigin())
				.isEqualTo(TagOrigin.MANUAL);
		assertThat(tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Auto-matched").orElseThrow().getOrigin())
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

	@Test
	void derivesLapsFromTheMatchedWorkoutByDefault() {
		User athlete = newAthlete("wm-laps-default@example.cc", false, false);
		Workout workout = newSingleStepWorkout(athlete);
		schedule(athlete, workout, LocalDate.of(2026, 6, 22));
		Instant start = Instant.parse("2026-06-22T06:30:00Z");
		Activity activity = newActivity(athlete, "Morning run", start);
		seedRecords(activity, start, 300);

		autoMatchService.attemptMatch(activity.getId());

		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).getWorkoutStep()).isNotNull();
	}

	@Test
	void leavesLapsUntouchedWhenTheAthletePrefersTheOriginalFitLaps() {
		User athlete = newAthlete("wm-laps-original@example.cc", false, false);
		athlete.setLapSource(LapSource.ORIGINAL);
		userRepository.save(athlete);
		Workout workout = newSingleStepWorkout(athlete);
		schedule(athlete, workout, LocalDate.of(2026, 6, 23));
		Instant start = Instant.parse("2026-06-23T06:30:00Z");
		Activity activity = newActivity(athlete, "Morning run", start);
		seedRecords(activity, start, 300);
		Lap existing = new Lap();
		existing.setActivity(activity);
		existing.setIndex(1);
		existing.setDuration(301);
		existing.setDistanceKm(1.0);
		lapRepository.save(existing);

		autoMatchService.attemptMatch(activity.getId());

		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).getWorkoutStep()).isNull();
	}
}
