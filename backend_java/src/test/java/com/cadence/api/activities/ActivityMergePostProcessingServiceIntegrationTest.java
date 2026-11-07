package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #501 (phase 1, part 3): mirrors backend's activities/tests/test_merge_postprocess.py. */
class ActivityMergePostProcessingServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T07:00:00Z");

	@Autowired
	private ActivityMergePostProcessingService postProcessingService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private DurationCurveRepository durationCurveRepository;
	@Autowired
	private BestEffortRepository bestEffortRepository;
	@Autowired
	private WorkoutRepository workoutRepository;
	@Autowired
	private ScheduledWorkoutRepository scheduledWorkoutRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(200);
		user.setLthr(160);
		return userRepository.save(user);
	}

	private Activity newBikeActivity(User athlete) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Target");
		activity.setStartDate(START);
		activity.setMovingTime(600);
		activity.setTss(50);
		return activityRepository.save(activity);
	}

	private void addRecords(Activity activity, int count, int power, int heartrate) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			record.setHeartrate(heartrate);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void recomputesDerivedStatsFromRewrittenRecords() {
		User athlete = newAthlete("merge-postprocess-stats@example.cc");
		Activity activity = newBikeActivity(athlete);
		addRecords(activity, 600, 250, 165);
		assertThat(activity.getMaxPower()).isNull();

		postProcessingService.recomputeAfterMerge(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getMaxPower()).isEqualTo(250);
		assertThat(reloaded.getAvgPower()).isEqualTo(250);
	}

	@Test
	void runsTheFullAthleteThresholdLedgerTssCascade() {
		User athlete = newAthlete("merge-postprocess-tss@example.cc");
		Activity activity = newBikeActivity(athlete);
		addRecords(activity, 600, 220, 165);

		postProcessingService.recomputeAfterMerge(activity.getId());

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		// 220W against a 200W FTP for 10 minutes rates to a non-trivial, non-default TSS.
		assertThat(reloaded.getTss()).isNotEqualTo(50).isGreaterThan(0);
	}

	@Test
	void writesDurationCurveAndBestEffortsForAnOrdinaryActivity() {
		User athlete = newAthlete("merge-postprocess-curve@example.cc");
		Activity activity = newBikeActivity(athlete);
		addRecords(activity, 600, 250, 165);

		postProcessingService.recomputeAfterMerge(activity.getId());

		assertThat(durationCurveRepository.findByActivityIdAndMetric(activity.getId(), DurationCurveMetric.POWER))
				.isPresent();
		assertThat(bestEffortRepository.countByAthleteIdAndActivitySport(athlete.getId(), Sport.BIKE)).isGreaterThan(0);
	}

	@Test
	void skipsDurationCurveAndBestEffortsForAMultisportParent() {
		User athlete = newAthlete("merge-postprocess-multisport@example.cc");
		Activity activity = newBikeActivity(athlete);
		activity.setSport(Sport.MULTISPORT);
		activityRepository.save(activity);
		addRecords(activity, 600, 250, 165);

		postProcessingService.recomputeAfterMerge(activity.getId());

		assertThat(durationCurveRepository.findByActivityIdAndMetric(activity.getId(), DurationCurveMetric.POWER))
				.isEmpty();
		assertThat(bestEffortRepository.countByAthleteId(athlete.getId())).isZero();
	}

	@Test
	void matchesASameDayScheduledWorkout() {
		User athlete = newAthlete("merge-postprocess-match@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Merged ride");
		workout.setSport(Sport.BIKE);
		workout = workoutRepository.save(workout);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(START.atZone(java.time.ZoneOffset.UTC).toLocalDate());
		scheduled.setStatus(ScheduledWorkoutStatus.PLANNED);
		scheduled = scheduledWorkoutRepository.save(scheduled);

		Activity activity = newBikeActivity(athlete);
		addRecords(activity, 600, 250, 165);

		postProcessingService.recomputeAfterMerge(activity.getId());

		ScheduledWorkout reloadedScheduled = scheduledWorkoutRepository.findById(scheduled.getId()).orElseThrow();
		Activity reloadedActivity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloadedScheduled.getStatus()).isEqualTo(ScheduledWorkoutStatus.COMPLETED);
		assertThat(reloadedActivity.getWorkout()).isNotNull();
		assertThat(reloadedActivity.getWorkout().getId()).isEqualTo(workout.getId());
	}
}
