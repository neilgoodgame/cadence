package com.cadence.api.mcp.tools.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.ActivityMergeService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.activities.dto.TagResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ActivityReadToolsTest extends IntegrationTest {

	@Autowired
	private ActivityReadTools activityReadTools;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private LapRepository lapRepository;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private ActivityTagRepository activityTagRepository;

	@Autowired
	private ActivityMergeService activityMergeService;

	@Autowired
	private RecordRepository recordRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));
	}

	// Regression test: getActivityLaps used to read laps via the plain (non-fetch-joined)
	// repository method, then LapMapper read workoutStep's fields - which threw
	// LazyInitializationException once this method's own implicit transaction closed
	// (open-in-view is off), for any lap actually linked to a WorkoutStep. Only surfaced once a
	// real matched-workout activity had derived laps to read back through this tool.
	@Test
	void getActivityLapsIncludesStepContextForADerivedLap() {
		User athlete = newUser("read-tool-laps-athlete@example.cc");
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("W");
		workout.setSport(Sport.BIKE);
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(0);
		step.setKind(StepKind.BLOCK);
		step.setEndType(StepEndType.TIME);
		step.setDuration(300);
		step.setTargetType(TargetType.POWER);
		step.setTargetLow(100.0);
		step.setTargetHigh(110.0);
		workout.getSteps().add(step);
		workout = workoutRepository.saveAndFlush(workout);

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(Instant.parse("2026-01-01T06:00:00Z"));
		activity.setWorkout(workout);
		activity = activityRepository.save(activity);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(1);
		lap.setDuration(300);
		lap.setDistanceKm(3.0);
		lap.setWorkoutStep(step);
		lapRepository.save(lap);
		authAs(athlete.getId(), "activities:read");

		var laps = activityReadTools.getActivityLaps(activity.getId());

		assertThat(laps).hasSize(1);
		assertThat(laps.get(0).stepKind()).isEqualTo(StepKind.BLOCK);
	}

	private Activity newActivityWithRecords(User athlete, Instant start, int count, int power) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike ride");
		activity.setStartDate(start);
		activity = activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	@Test
	void getMergeReturnsTheActiveMergeRecord() {
		User athlete = newUser("read-tool-get-merge-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivityWithRecords(athlete, start, 10, 100);
		Activity source = newActivityWithRecords(athlete, start, 10, 250);
		source.setName("Source ride");
		activityRepository.save(source);
		activityMergeService.runMerge(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0, true, false);
		authAs(athlete.getId(), "activities:read");

		var merge = activityReadTools.getMerge(target.getId());

		assertThat(merge).isNotNull();
		assertThat(merge.mergeId()).isNotNull();
		assertThat(merge.sourceId()).isEqualTo(source.getId());
		assertThat(merge.sourceName()).isEqualTo("Source ride");
		assertThat(merge.fields()).containsExactly("power");
		assertThat(merge.nullPolicy()).isEqualTo(NullPolicy.OVERWRITE);
		assertThat(merge.sourceHidden()).isTrue();
	}

	@Test
	void getMergeReturnsNullWhenThereIsNoActiveMerge() {
		User athlete = newUser("read-tool-get-merge-none-athlete@example.cc");
		Activity activity = newActivityWithRecords(athlete, Instant.parse("2026-06-01T18:00:00Z"), 10, 100);
		authAs(athlete.getId(), "activities:read");

		assertThat(activityReadTools.getMerge(activity.getId())).isNull();
	}

	@Test
	void getMergeRejectsAnOutsiderWithNoShare() {
		User athlete = newUser("read-tool-get-merge-outsider-athlete@example.cc");
		Activity activity = newActivityWithRecords(athlete, Instant.parse("2026-06-01T18:00:00Z"), 10, 100);
		User outsider = newUser("read-tool-get-merge-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> activityReadTools.getMerge(activity.getId())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void listActivitiesQueryFiltersAndReturnsHeatStrainStats() {
		// This is the motivating case: an MCP client asking "which sessions had the highest
		// heat strain" needs both the CQL filter AND the actual value back in one call, not a
		// separate get_activity_stream_summary per activity.
		User athlete = newUser("mcp-list-heat-strain@example.cc");
		Activity severe = new Activity();
		severe.setAthlete(athlete);
		severe.setSport(Sport.BIKE);
		severe.setName("Severe");
		severe.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		severe.setAvgHeatStrain(1.5);
		severe.setMaxHeatStrain(4.2);
		activityRepository.save(severe);

		Activity mild = new Activity();
		mild.setAthlete(athlete);
		mild.setSport(Sport.BIKE);
		mild.setName("Mild");
		mild.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		mild.setAvgHeatStrain(0.5);
		mild.setMaxHeatStrain(1.1);
		activityRepository.save(mild);

		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.listActivities("max_heat_strain>3", null, null, null, null, null, null);

		assertThat(result.data()).hasSize(1);
		assertThat(result.data().get(0).name()).isEqualTo("Severe");
		assertThat(result.data().get(0).maxHeatStrain()).isEqualTo(4.2);
		assertThat(result.data().get(0).avgHeatStrain()).isEqualTo(1.5);
	}

	@Test
	void getActivityIncludesAverageAirTemperatureAndHumidity() {
		User athlete = newUser("read-tool-env-athlete@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Indoor trainer ride");
		activity.setStartDate(Instant.parse("2026-01-01T06:00:00Z"));
		activity.setAvgAirTemp(24.5);
		activity.setAvgHumidity(58);
		activity = activityRepository.save(activity);
		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.getActivity(activity.getId());

		assertThat(result.avgAirTemp()).isEqualTo(24.5);
		assertThat(result.avgHumidity()).isEqualTo(58);
	}

	@Test
	void getActivityIncludesWeightAndFluidsForSweatRate() {
		User athlete = newUser("read-tool-weigh-in-athlete@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Hot long run");
		activity.setStartDate(Instant.parse("2026-01-01T06:00:00Z"));
		activity.setStartWeightKg(72.4);
		activity.setEndWeightKg(71.1);
		activity.setFluidsMl(600);
		activity = activityRepository.save(activity);
		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.getActivity(activity.getId());

		assertThat(result.startWeightKg()).isEqualTo(72.4);
		assertThat(result.endWeightKg()).isEqualTo(71.1);
		assertThat(result.fluidsMl()).isEqualTo(600);
	}

	@Test
	void listActivitiesQueryFiltersByFluidsMl() {
		User athlete = newUser("read-tool-list-fluids-athlete@example.cc");
		Activity chugged = new Activity();
		chugged.setAthlete(athlete);
		chugged.setSport(Sport.RUN);
		chugged.setName("Chugged");
		chugged.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		chugged.setFluidsMl(900);
		activityRepository.save(chugged);

		Activity sipped = new Activity();
		sipped.setAthlete(athlete);
		sipped.setSport(Sport.RUN);
		sipped.setName("Sipped");
		sipped.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		sipped.setFluidsMl(200);
		activityRepository.save(sipped);

		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.listActivities("fluids_ml>500", null, null, null, null, null, null);

		assertThat(result.data()).hasSize(1);
		assertThat(result.data().get(0).name()).isEqualTo("Chugged");
	}

	@Test
	void listActivitiesIncludeDuplicatesOverridesTheDefaultExclusion() {
		// Issue #498: a same-ride-twice duplicate is hidden by default, but includeDuplicates
		// surfaces it - e.g. to find a specific pair to resolve.
		User athlete = newUser("read-tool-list-dup-athlete@example.cc");
		Activity primary = new Activity();
		primary.setAthlete(athlete);
		primary.setSport(Sport.BIKE);
		primary.setName("Primary");
		primary.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		activityRepository.save(primary);
		Activity duplicate = new Activity();
		duplicate.setAthlete(athlete);
		duplicate.setSport(Sport.BIKE);
		duplicate.setName("Duplicate");
		duplicate.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		duplicate.setPrimaryActivity(primary);
		activityRepository.save(duplicate);
		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.listActivities(null, null, null, null, null, null, null);
		assertThat(result.data()).extracting(r -> r.id()).containsExactly(primary.getId());

		var resultWithDup = activityReadTools.listActivities(null, null, null, null, null, null, true);
		assertThat(resultWithDup.data()).extracting(r -> r.id()).containsExactlyInAnyOrder(primary.getId(), duplicate.getId());
	}

	@Test
	void listTagsIncludesUsageCounts() {
		User athlete = newUser("read-tool-list-tags-athlete@example.cc");
		Tag tag = new Tag();
		tag.setAthlete(athlete);
		tag.setName("Race");
		tag = tagRepository.save(tag);
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity = activityRepository.save(activity);
		ActivityTag link = new ActivityTag();
		link.setActivity(activity);
		link.setTag(tag);
		activityTagRepository.save(link);
		authAs(athlete.getId(), "activities:read");

		var result = activityReadTools.listTags();

		assertThat(result).extracting(TagResponse::name, TagResponse::count).containsExactly(tuple("Race", 1L));
	}

	@Test
	void listOverlappingActivitiesDetectsASameRideTwicePair() {
		User athlete = newUser("mcp-overlap-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = new Activity();
		zwift.setAthlete(athlete);
		zwift.setSport(Sport.BIKE);
		zwift.setName("Zwift");
		zwift.setDevice("Zwift");
		zwift.setStartDate(start);
		zwift.setElapsedSeconds(3600);
		zwift.setMovingTime(3600);
		zwift = activityRepository.save(zwift);

		Activity garmin = new Activity();
		garmin.setAthlete(athlete);
		garmin.setSport(Sport.BIKE);
		garmin.setName("Garmin");
		garmin.setDevice("Garmin");
		garmin.setStartDate(start.plusSeconds(5));
		garmin.setElapsedSeconds(3600);
		garmin.setMovingTime(3600);
		garmin = activityRepository.save(garmin);
		authAs(athlete.getId(), "activities:read");

		var page = activityReadTools.listOverlappingActivities(
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

		assertThat(page.data()).hasSize(1);
		var pair = page.data().get(0);
		assertThat(java.util.List.of(pair.anchor().id(), pair.other().id()))
				.containsExactlyInAnyOrder(zwift.getId(), garmin.getId());
		assertThat(pair.status()).isEqualTo("unresolved");
	}

	@Test
	void listOverlappingActivitiesActivityIdPinsTheAnchor() {
		User athlete = newUser("mcp-overlap-pin-athlete@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = new Activity();
		zwift.setAthlete(athlete);
		zwift.setSport(Sport.BIKE);
		zwift.setName("Zwift");
		zwift.setDevice("Zwift");
		zwift.setStartDate(start);
		zwift.setElapsedSeconds(3600);
		zwift.setMovingTime(3600);
		zwift = activityRepository.save(zwift);

		Activity garmin = new Activity();
		garmin.setAthlete(athlete);
		garmin.setSport(Sport.BIKE);
		garmin.setName("Garmin");
		garmin.setDevice("Garmin");
		garmin.setStartDate(start.plusSeconds(5));
		garmin.setElapsedSeconds(3600);
		garmin.setMovingTime(3600);
		garmin = activityRepository.save(garmin);
		authAs(athlete.getId(), "activities:read");

		var page = activityReadTools.listOverlappingActivities(
				garmin.getId(), null, null, null, null, null, null, null, null, null, null, null, null, null, null);

		assertThat(page.data()).hasSize(1);
		assertThat(page.data().get(0).anchor().id()).isEqualTo(garmin.getId());
		assertThat(page.data().get(0).other().id()).isEqualTo(zwift.getId());
	}
}
