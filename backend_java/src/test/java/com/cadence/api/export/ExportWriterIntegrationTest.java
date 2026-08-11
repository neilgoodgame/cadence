package com.cadence.api.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeKind;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.Component;
import com.cadence.api.gear.ComponentRepository;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutStep;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the actual query + serialization path an export job runs, without the async/gzip
 * plumbing around it (ExportService) - that's just file/status bookkeeping, this is the part
 * with real behavior to get wrong. */
class ExportWriterIntegrationTest extends IntegrationTest {

	@Autowired
	private ExportWriter exportWriter;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private LapRepository lapRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private WorkoutRepository workoutRepository;
	@Autowired
	private ScheduledWorkoutRepository scheduledWorkoutRepository;
	@Autowired
	private BikeRepository bikeRepository;
	@Autowired
	private ComponentRepository componentRepository;
	@Autowired
	private ShoeRepository shoeRepository;
	@Autowired
	private ShoeModelRepository shoeModelRepository;
	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;
	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void writesEveryCategoryAndExcludesRetiredGear() throws Exception {
		User athlete = newUser("export-writer@example.cc");

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity = activityRepository.save(activity);

		Lap lap = new Lap();
		lap.setActivity(activity);
		lap.setIndex(0);
		lap.setDuration(600);
		lap.setDistanceKm(2.0);
		lapRepository.save(lap);

		Record record = new Record();
		record.setId(new RecordId(activity.getId(), Instant.parse("2026-01-01T07:00:00Z")));
		record.setActivity(activity);
		record.setT(0);
		record.setPower(200);
		record.setHeartrate(140);
		recordRepository.save(record);

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Local 10k");
		race.setDate(LocalDate.of(2026, 3, 1));
		raceRepository.save(race);

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("VO2 intervals");
		workout.setSport(Sport.RUN);
		WorkoutStep step = new WorkoutStep();
		step.setWorkout(workout);
		step.setOrder(0);
		step.setKind(StepKind.BLOCK);
		step.setEndType(StepEndType.TIME);
		step.setDuration(300);
		workout.getSteps().add(step);
		workout = workoutRepository.save(workout);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(LocalDate.of(2026, 3, 5));
		scheduledWorkoutRepository.save(scheduled);

		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Road bike");
		bike.setKind(BikeKind.ROAD);
		bike = bikeRepository.save(bike);

		Component component = new Component();
		component.setBike(bike);
		component.setName("Chain");
		componentRepository.save(component);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("Sabre");
		shoeModel.setModel("Runner");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion shoeModelVersion = new ShoeModelVersion();
		shoeModelVersion.setShoeModel(shoeModel);
		shoeModelVersion.setVersion("v3");
		shoeModelVersion = shoeModelVersionRepository.save(shoeModelVersion);

		Shoe activeShoe = new Shoe();
		activeShoe.setAthlete(athlete);
		activeShoe.setShoeModelVersion(shoeModelVersion);
		activeShoe.setName("Daily trainer");
		shoeRepository.save(activeShoe);

		Shoe retiredShoe = new Shoe();
		retiredShoe.setAthlete(athlete);
		retiredShoe.setShoeModelVersion(shoeModelVersion);
		retiredShoe.setName("Old racers");
		retiredShoe.setRetired(true);
		shoeRepository.save(retiredShoe);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), null, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());
		assertThat(root.get("athlete_id").asText()).isEqualTo(athlete.getId());

		JsonNode activityEntry = root.get("activities").get(0);
		assertThat(root.get("activities")).hasSize(1);
		assertThat(activityEntry.get("activity").get("name").asText()).isEqualTo("Morning Run");
		assertThat(activityEntry.get("laps")).hasSize(1);
		assertThat(activityEntry.get("streams").get("fields").get("power").get(0).asInt()).isEqualTo(200);

		assertThat(root.get("races")).hasSize(1);
		assertThat(root.get("races").get(0).get("name").asText()).isEqualTo("Local 10k");

		assertThat(root.get("workouts")).hasSize(1);
		assertThat(root.get("workouts").get(0).get("steps")).hasSize(1);

		assertThat(root.get("scheduled_workouts")).hasSize(1);

		JsonNode equipment = root.get("equipment");
		assertThat(equipment.get("bikes")).hasSize(1);
		assertThat(equipment.get("components")).hasSize(1);
		assertThat(equipment.get("shoes")).hasSize(1);
		assertThat(equipment.get("shoes").get(0).get("name").asText()).isEqualTo("Daily trainer");
	}

	@Test
	void sportFilterExcludesOtherDisciplinesButIncludesMatchingMultisportLegsAndAllEquipment() throws Exception {
		User athlete = newUser("export-sport-filter@example.cc");

		Activity bikeActivity = new Activity();
		bikeActivity.setAthlete(athlete);
		bikeActivity.setSport(Sport.BIKE);
		bikeActivity.setName("Standalone Ride");
		bikeActivity.setStartDate(Instant.parse("2026-02-01T07:00:00Z"));
		activityRepository.save(bikeActivity);

		Activity runActivity = new Activity();
		runActivity.setAthlete(athlete);
		runActivity.setSport(Sport.RUN);
		runActivity.setName("Standalone Run");
		runActivity.setStartDate(Instant.parse("2026-02-02T07:00:00Z"));
		activityRepository.save(runActivity);

		Activity multisportParent = new Activity();
		multisportParent.setAthlete(athlete);
		multisportParent.setSport(Sport.MULTISPORT);
		multisportParent.setName("Triathlon");
		multisportParent.setStartDate(Instant.parse("2026-02-03T07:00:00Z"));
		multisportParent = activityRepository.save(multisportParent);

		Activity bikeLeg = new Activity();
		bikeLeg.setAthlete(athlete);
		bikeLeg.setSport(Sport.BIKE);
		bikeLeg.setName("Triathlon Bike Leg");
		bikeLeg.setStartDate(Instant.parse("2026-02-03T08:00:00Z"));
		bikeLeg.setParentActivity(multisportParent);
		activityRepository.save(bikeLeg);

		Race bikeRace = new Race();
		bikeRace.setAthlete(athlete);
		bikeRace.setName("Local Crit");
		bikeRace.setSport(Sport.BIKE);
		bikeRace.setDate(LocalDate.of(2026, 2, 1));
		raceRepository.save(bikeRace);

		Race runRace = new Race();
		runRace.setAthlete(athlete);
		runRace.setName("Local 5k");
		runRace.setSport(Sport.RUN);
		runRace.setDate(LocalDate.of(2026, 2, 2));
		raceRepository.save(runRace);

		Workout bikeWorkout = new Workout();
		bikeWorkout.setCreatedBy(athlete);
		bikeWorkout.setName("FTP Test");
		bikeWorkout.setSport(Sport.BIKE);
		WorkoutStep bikeStep = new WorkoutStep();
		bikeStep.setWorkout(bikeWorkout);
		bikeStep.setOrder(0);
		bikeStep.setKind(StepKind.BLOCK);
		bikeStep.setEndType(StepEndType.TIME);
		bikeStep.setDuration(1200);
		bikeWorkout.getSteps().add(bikeStep);
		workoutRepository.save(bikeWorkout);

		Workout runWorkout = new Workout();
		runWorkout.setCreatedBy(athlete);
		runWorkout.setName("Tempo Run");
		runWorkout.setSport(Sport.RUN);
		WorkoutStep runStep = new WorkoutStep();
		runStep.setWorkout(runWorkout);
		runStep.setOrder(0);
		runStep.setKind(StepKind.BLOCK);
		runStep.setEndType(StepEndType.TIME);
		runStep.setDuration(1200);
		runWorkout.getSteps().add(runStep);
		workoutRepository.save(runWorkout);

		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("TT Bike");
		bike.setKind(BikeKind.TT);
		bikeRepository.save(bike);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("SportFilterCo");
		shoeModel.setModel("Trainer");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion shoeModelVersion = new ShoeModelVersion();
		shoeModelVersion.setShoeModel(shoeModel);
		shoeModelVersion.setVersion("v1");
		shoeModelVersion = shoeModelVersionRepository.save(shoeModelVersion);
		Shoe shoe = new Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(shoeModelVersion);
		shoe.setName("Daily trainer");
		shoeRepository.save(shoe);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), Sport.BIKE, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());

		assertThat(root.get("activities")).hasSize(2);
		List<String> activityNames = new ArrayList<>();
		root.get("activities").forEach(entry -> activityNames.add(entry.get("activity").get("name").asText()));
		assertThat(activityNames).containsExactlyInAnyOrder("Standalone Ride", "Triathlon Bike Leg");

		assertThat(root.get("races")).hasSize(1);
		assertThat(root.get("races").get(0).get("name").asText()).isEqualTo("Local Crit");

		assertThat(root.get("workouts")).hasSize(1);
		assertThat(root.get("workouts").get(0).get("name").asText()).isEqualTo("FTP Test");

		// Equipment is always exported in full, regardless of the sport filter.
		JsonNode equipment = root.get("equipment");
		assertThat(equipment.get("bikes")).hasSize(1);
		assertThat(equipment.get("shoes")).hasSize(1);
	}

	@Test
	void writeCallsOnStepForEverySectionInOrderEvenWithNoData() throws Exception {
		User athlete = newUser("export-progress-steps@example.cc");

		List<String> seen = new ArrayList<>();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), null, generator, seen::add);
		}

		assertThat(seen).containsExactly("equipment", "workouts", "activities", "races", "scheduled_workouts");
	}

	@Test
	void sportFilterIncludesALinkedRaceWhoseOwnSportWasNeverSet() throws Exception {
		// Reproduces linking to an *existing* race via the UI (as opposed to "mark this
		// activity as a race", which sets sport at creation) - the race's own sport stays
		// null even though it's now tied to a bike activity.
		User athlete = newUser("export-linked-race-no-sport@example.cc");

		Activity bikeActivity = new Activity();
		bikeActivity.setAthlete(athlete);
		bikeActivity.setSport(Sport.BIKE);
		bikeActivity.setName("Another Ride");
		bikeActivity.setStartDate(Instant.parse("2026-03-01T07:00:00Z"));
		activityRepository.save(bikeActivity);

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Linked, sport never set");
		race.setDate(LocalDate.of(2026, 3, 1));
		race.setActivity(bikeActivity);
		raceRepository.save(race);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), Sport.BIKE, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());
		assertThat(root.get("races")).hasSize(1);
		assertThat(root.get("races").get(0).get("name").asText()).isEqualTo("Linked, sport never set");
	}

	@Test
	void sportFilterStillExcludesARaceWithNoSportAndNoLinkedActivity() throws Exception {
		User athlete = newUser("export-unlinked-race-no-sport@example.cc");

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Unlinked, no sport");
		race.setDate(LocalDate.of(2026, 3, 2));
		raceRepository.save(race);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), Sport.BIKE, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());
		assertThat(root.get("races")).isEmpty();
	}

	@Test
	void countsReflectTheFullUnfilteredAccount() throws Exception {
		User athlete = newUser("export-counts-full@example.cc");

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-04-01T07:00:00Z"));
		activityRepository.save(activity);

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Local 10k");
		race.setDate(LocalDate.of(2026, 4, 1));
		raceRepository.save(race);

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Tempo Run");
		workout.setSport(Sport.RUN);
		workoutRepository.save(workout);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(LocalDate.of(2026, 4, 5));
		scheduledWorkoutRepository.save(scheduled);

		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Gravel bike");
		bike.setKind(BikeKind.GRAVEL);
		bike = bikeRepository.save(bike);

		Component component = new Component();
		component.setBike(bike);
		component.setName("Chain");
		componentRepository.save(component);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("CountsCo");
		shoeModel.setModel("Runner");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion shoeModelVersion = new ShoeModelVersion();
		shoeModelVersion.setShoeModel(shoeModel);
		shoeModelVersion.setVersion("v1");
		shoeModelVersion = shoeModelVersionRepository.save(shoeModelVersion);

		Shoe activeShoe = new Shoe();
		activeShoe.setAthlete(athlete);
		activeShoe.setShoeModelVersion(shoeModelVersion);
		activeShoe.setName("Daily trainer");
		shoeRepository.save(activeShoe);

		Shoe retiredShoe = new Shoe();
		retiredShoe.setAthlete(athlete);
		retiredShoe.setShoeModelVersion(shoeModelVersion);
		retiredShoe.setName("Old racers");
		retiredShoe.setRetired(true);
		shoeRepository.save(retiredShoe);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), null, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());
		JsonNode counts = root.get("counts");
		assertThat(counts.get("activities").asInt()).isEqualTo(1);
		assertThat(counts.get("races").asInt()).isEqualTo(1);
		assertThat(counts.get("workouts").asInt()).isEqualTo(1);
		assertThat(counts.get("scheduled_workouts").asInt()).isEqualTo(1);
		assertThat(counts.get("bikes").asInt()).isEqualTo(1);
		assertThat(counts.get("shoes").asInt()).isEqualTo(1); // retired excluded
		assertThat(counts.get("components").asInt()).isEqualTo(1);

		// Genuinely matches the rest of the same file, not just a hardcoded number.
		assertThat(counts.get("activities").asInt()).isEqualTo(root.get("activities").size());
		assertThat(counts.get("races").asInt()).isEqualTo(root.get("races").size());
		assertThat(counts.get("workouts").asInt()).isEqualTo(root.get("workouts").size());
		assertThat(counts.get("scheduled_workouts").asInt()).isEqualTo(root.get("scheduled_workouts").size());
		assertThat(counts.get("bikes").asInt()).isEqualTo(root.get("equipment").get("bikes").size());
		assertThat(counts.get("shoes").asInt()).isEqualTo(root.get("equipment").get("shoes").size());
		assertThat(counts.get("components").asInt()).isEqualTo(root.get("equipment").get("components").size());
	}

	@Test
	void countsReflectTheSportFilteredScope() throws Exception {
		User athlete = newUser("export-counts-filtered@example.cc");

		Activity bikeActivity = new Activity();
		bikeActivity.setAthlete(athlete);
		bikeActivity.setSport(Sport.BIKE);
		bikeActivity.setName("Ride");
		bikeActivity.setStartDate(Instant.parse("2026-04-02T07:00:00Z"));
		activityRepository.save(bikeActivity);

		Activity runActivity = new Activity();
		runActivity.setAthlete(athlete);
		runActivity.setSport(Sport.RUN);
		runActivity.setName("Run");
		runActivity.setStartDate(Instant.parse("2026-04-03T07:00:00Z"));
		activityRepository.save(runActivity);

		Race bikeRace = new Race();
		bikeRace.setAthlete(athlete);
		bikeRace.setName("Crit");
		bikeRace.setSport(Sport.BIKE);
		bikeRace.setDate(LocalDate.of(2026, 4, 2));
		raceRepository.save(bikeRace);

		Race runRace = new Race();
		runRace.setAthlete(athlete);
		runRace.setName("5k");
		runRace.setSport(Sport.RUN);
		runRace.setDate(LocalDate.of(2026, 4, 3));
		raceRepository.save(runRace);

		Workout runWorkout = new Workout();
		runWorkout.setCreatedBy(athlete);
		runWorkout.setName("Tempo");
		runWorkout.setSport(Sport.RUN);
		workoutRepository.save(runWorkout);

		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Road bike");
		bike.setKind(BikeKind.ROAD);
		bikeRepository.save(bike);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), Sport.BIKE, generator);
		}

		JsonNode root = jsonMapper.readTree(out.toByteArray());
		JsonNode counts = root.get("counts");
		assertThat(counts.get("activities").asInt()).isEqualTo(1);
		assertThat(counts.get("races").asInt()).isEqualTo(1);
		assertThat(counts.get("workouts").asInt()).isEqualTo(0);
		assertThat(counts.get("scheduled_workouts").asInt()).isEqualTo(0);
		// Equipment stays full regardless of the filter.
		assertThat(counts.get("bikes").asInt()).isEqualTo(1);
	}

	@Test
	void onTotalAndOnProgressReachTheFullItemCount() throws Exception {
		// Same seed shape as countsReflectTheFullUnfilteredAccount - 1 activity, 1 race, 1
		// workout, 1 scheduled_workout, 1 bike, 1 active shoe (retired excluded), 1 component = 7.
		User athlete = newUser("export-progress-items@example.cc");

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-04-01T07:00:00Z"));
		activityRepository.save(activity);

		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Local 10k");
		race.setDate(LocalDate.of(2026, 4, 1));
		raceRepository.save(race);

		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName("Tempo Run");
		workout.setSport(Sport.RUN);
		workoutRepository.save(workout);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(athlete);
		scheduled.setDate(LocalDate.of(2026, 4, 5));
		scheduledWorkoutRepository.save(scheduled);

		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Gravel bike");
		bike.setKind(BikeKind.GRAVEL);
		bike = bikeRepository.save(bike);

		Component component = new Component();
		component.setBike(bike);
		component.setName("Chain");
		componentRepository.save(component);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("ProgressCo");
		shoeModel.setModel("Runner");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion shoeModelVersion = new ShoeModelVersion();
		shoeModelVersion.setShoeModel(shoeModel);
		shoeModelVersion.setVersion("v1");
		shoeModelVersion = shoeModelVersionRepository.save(shoeModelVersion);

		Shoe activeShoe = new Shoe();
		activeShoe.setAthlete(athlete);
		activeShoe.setShoeModelVersion(shoeModelVersion);
		activeShoe.setName("Daily trainer");
		shoeRepository.save(activeShoe);

		// onTotal/onProgress are scoped to the *current* section, not a blend across all of
		// them - equipment (bike+shoe+component) totals 3, every other section totals 1.
		List<String> events = new ArrayList<>();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), null, generator, step -> events.add("step:" + step),
					total -> events.add("total:" + total), processed -> events.add("progress:" + processed));
		}

		assertThat(events).containsExactly(
				"step:equipment", "total:3", "progress:3",
				"step:workouts", "total:1", "progress:1",
				"step:activities", "total:1", "progress:1",
				"step:races", "total:1", "progress:1",
				"step:scheduled_workouts", "total:1", "progress:1");
	}

	@Test
	void exportCarriesFtpSnapshotButStripsSuggestedFtp() throws Exception {
		User athlete = newUser("export-threshold-snapshot@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setFtpSnapshot(200);
		activity.setSuggestedFtp(260);
		activityRepository.save(activity);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JsonGenerator generator = jsonMapper.createGenerator(out)) {
			exportWriter.write(athlete.getId(), null, generator);
		}

		JsonNode activityNode = jsonMapper.readTree(out.toByteArray()).get("activities").get(0).get("activity");
		assertThat(activityNode.get("ftp_snapshot").asInt()).isEqualTo(200);
		// Nulled out, not omitted (this codebase's Jackson config doesn't drop null fields) -
		// either way ImportReader.buildActivity never reads suggested_ftp back off the source
		// JSON at all, so the exact shape doesn't affect round-trip behavior.
		assertThat(activityNode.get("suggested_ftp").isNull()).isTrue();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}
}
