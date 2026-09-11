package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.dto.WorkoutMatchComparisonResponse;
import com.cadence.api.workouts.dto.WorkoutMatchResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

	@Autowired
	private LapRepository lapRepository;

	@Autowired
	private RecordRepository recordRepository;

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

	private WorkoutStep newStep(Workout workout, StepKind kind, int order, int duration) {
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(order);
		step.setKind(kind);
		step.setEndType(StepEndType.TIME);
		step.setDuration(duration);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(100.0);
		step.setTargetHigh(100.0);
		return step;
	}

	private Lap newLap(Activity activity, int index, int duration, int avgPower, WorkoutStep workoutStep) {
		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(index);
		lap.setDuration(duration);
		lap.setDistanceKm(1.0);
		lap.setAvgPower(avgPower);
		lap.setWorkoutStep(workoutStep);
		return lapRepository.save(lap);
	}

	private Map<String, WorkoutMatchComparisonResponse> compareByActivityId(String workoutId) {
		return workoutMatchService.listComparison(workoutId).stream()
				.collect(java.util.stream.Collectors.toMap(WorkoutMatchComparisonResponse::activityId, Function.identity()));
	}

	@Test
	void comparisonIncludesEfAndEnvironmentFieldsFromTheActivity() {
		User athlete = newAthlete("wm-compare-ef@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity activity = newActivity(athlete, workout, "Ride", 1200, 35.0, 210, 33);
		activity.setAvgHr(125);
		activity.setAvgAirTemp(22.5);
		activity.setAvgHumidity(45);
		activityRepository.save(activity);

		WorkoutMatchComparisonResponse row = compareByActivityId(workout.getId()).get(activity.getId());

		assertThat(row.ef()).isCloseTo(210.0 / 125, org.assertj.core.data.Offset.offset(0.001));
		assertThat(row.avgAirTemp()).isEqualTo(22.5);
		assertThat(row.avgHumidity()).isEqualTo(45);
	}

	@Test
	void comparisonEfIsNullWhenHrIsMissing() {
		User athlete = newAthlete("wm-compare-no-hr@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity activity = newActivity(athlete, workout, "Ride", 1200, 35.0, 210, 33);

		WorkoutMatchComparisonResponse row = compareByActivityId(workout.getId()).get(activity.getId());

		assertThat(row.ef()).isNull();
	}

	@Test
	void comparisonWorkBlockPowerIsDurationWeightedAcrossBlockLaps() {
		User athlete = newAthlete("wm-compare-blocks@example.cc");
		// Built fresh (not via newWorkout, which already saves once) and cascade-saved in one
		// shot - re-saving an already-persisted Workout after adding new transient steps to its
		// collection triggers a merge, not a clean persist, and throws
		// TransientPropertyValueException for those steps (the same pitfall documented on
		// WorkoutMatchScanServiceTest.newGorbyWorkout).
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("VO2 Max 5x5");
		workout.setSport(Sport.BIKE);
		workout.setDuration(1200);
		workout.setTss(33);
		WorkoutStep blockStep = newStep(workout, StepKind.BLOCK, 0, 300);
		WorkoutStep recStep = newStep(workout, StepKind.REC, 1, 60);
		workout.getSteps().add(blockStep);
		workout.getSteps().add(recStep);
		workout = workoutRepository.saveAndFlush(workout);
		Activity activity = newActivity(athlete, workout, "Ride", 1200, 35.0, 210, 33);
		// A short, high-power block and a long, lower-power block - a bare mean-of-laps would
		// give (300+100)/2=200; duration-weighted gives (300*60 + 100*240)/300 = 140.
		newLap(activity, 0, 60, 300, blockStep);
		newLap(activity, 1, 240, 100, blockStep);
		// A non-block lap must not be counted.
		newLap(activity, 2, 60, 9999, recStep);

		WorkoutMatchComparisonResponse row = compareByActivityId(workout.getId()).get(activity.getId());

		assertThat(row.workBlockAvgPower()).isEqualTo(140);
	}

	@Test
	void comparisonWorkBlockPowerIsNullWithNoDerivedLaps() {
		User athlete = newAthlete("wm-compare-no-laps@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity activity = newActivity(athlete, workout, "Ride", 1200, 35.0, 210, 33);

		WorkoutMatchComparisonResponse row = compareByActivityId(workout.getId()).get(activity.getId());

		assertThat(row.workBlockAvgPower()).isNull();
	}

	@Test
	void comparisonAvgCoreTempAggregatesRecordsAndIsNullWithoutSensorData() {
		User athlete = newAthlete("wm-compare-core-temp@example.cc");
		Workout workout = newWorkout(athlete, 1200, 33);
		Activity withSensor = newActivity(athlete, workout, "With sensor", 1200, 35.0, 210, 33);
		Instant start = withSensor.getStartDate();
		Record r1 = new Record();
		r1.setId(new RecordId(withSensor.getId(), start));
		r1.setActivity(withSensor);
		r1.setT(0);
		r1.setCoreTemp(37.0);
		recordRepository.save(r1);
		Record r2 = new Record();
		r2.setId(new RecordId(withSensor.getId(), start.plusSeconds(1)));
		r2.setActivity(withSensor);
		r2.setT(1);
		r2.setCoreTemp(37.4);
		recordRepository.save(r2);
		Activity withoutSensor = newActivity(athlete, workout, "Without sensor", 1200, 35.0, 210, 33);
		Record r3 = new Record();
		r3.setId(new RecordId(withoutSensor.getId(), withoutSensor.getStartDate()));
		r3.setActivity(withoutSensor);
		r3.setT(0);
		recordRepository.save(r3);

		Map<String, WorkoutMatchComparisonResponse> byId = compareByActivityId(workout.getId());

		assertThat(byId.get(withSensor.getId()).avgCoreTemp()).isCloseTo(37.2, org.assertj.core.data.Offset.offset(0.1));
		assertThat(byId.get(withoutSensor.getId()).avgCoreTemp()).isNull();
	}
}
