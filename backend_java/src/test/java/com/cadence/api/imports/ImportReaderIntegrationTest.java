package com.cadence.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.TagService;
import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.export.ExportWriter;
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
import com.cadence.api.imports.dto.ImportCounts;
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
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/** Exercises a real export -> import round trip: seed one athlete, export them, import the bytes
 * into a different, fresh athlete, and confirm every cross-reference got remapped to the new ids -
 * not just that rows exist. Includes a multisport parent/child pair specifically to exercise the
 * deferred parent_activity_id fixup (see ImportReader's Javadoc). */
class ImportReaderIntegrationTest extends IntegrationTest {

	@Autowired
	private ExportWriter exportWriter;
	@Autowired
	private ImportReader importReader;
	@Autowired
	private JsonMapper jsonMapper;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ActivityService activityService;
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
	private TagService tagService;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void roundTripsEveryCategoryWithRemappedReferences() throws IOException {
		User source = newUser("import-source@example.cc");
		User target = newUser("import-target@example.cc");

		Bike bike = new Bike();
		bike.setAthlete(source);
		bike.setName("Road bike");
		bike.setKind(BikeKind.ROAD);
		bike = bikeRepository.save(bike);

		Component component = new Component();
		component.setBike(bike);
		component.setName("Chain");
		componentRepository.save(component);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("ImportTestCo");
		shoeModel.setModel("Roundtrip");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion("v3");
		smv = shoeModelVersionRepository.save(smv);
		Shoe shoe = new Shoe();
		shoe.setAthlete(source);
		shoe.setShoeModelVersion(smv);
		shoe.setName("Daily trainer");
		shoeRepository.save(shoe);

		Workout workout = new Workout();
		workout.setCreatedBy(source);
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

		Activity parent = new Activity();
		parent.setAthlete(source);
		parent.setSport(Sport.MULTISPORT);
		parent.setName("Triathlon");
		parent.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		parent = activityRepository.save(parent);

		Activity child = new Activity();
		child.setAthlete(source);
		child.setSport(Sport.RUN);
		child.setName("Bike leg");
		child.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		child.setParentActivity(parent);
		child.setWorkout(workout);
		child.setShoe(shoe);
		child = activityRepository.save(child);

		Lap lap = new Lap();
		lap.setActivity(child);
		lap.setIndex(0);
		lap.setDuration(600);
		lap.setDistanceKm(2.0);
		lapRepository.save(lap);

		Record record = new Record();
		record.setId(new RecordId(child.getId(), Instant.parse("2026-01-01T08:00:00Z")));
		record.setActivity(child);
		record.setT(0);
		record.setPower(200);
		record.setHeartrate(140);
		recordRepository.save(record);

		tagService.attachTag(child, source, null, "race");

		Race race = new Race();
		race.setAthlete(source);
		race.setName("Local 10k");
		race.setDate(LocalDate.of(2026, 1, 1));
		race.setActivity(child);
		raceRepository.save(race);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(source);
		scheduled.setDate(LocalDate.of(2026, 1, 1));
		scheduled.setActivity(child);
		scheduledWorkoutRepository.save(scheduled);

		Path file = Files.createTempFile("import-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			ImportCounts counts = importReader.read(target.getId(), file);

			assertThat(counts.activitiesImported()).isEqualTo(2);
			assertThat(counts.racesImported()).isEqualTo(1);
			assertThat(counts.workoutsImported()).isEqualTo(1);
			assertThat(counts.scheduledWorkoutsImported()).isEqualTo(1);
			assertThat(counts.bikesImported()).isEqualTo(1);
			assertThat(counts.shoesImported()).isEqualTo(1);
			assertThat(counts.componentsImported()).isEqualTo(1);
			assertThat(counts.itemsSkipped()).isZero();

			List<Activity> imported = activityRepository.findByAthleteIdOrderByStartDate(target.getId());
			assertThat(imported).hasSize(2);
			Activity importedChild = imported.stream().filter(a -> a.getName().equals("Bike leg")).findFirst().orElseThrow();
			Activity importedParent = imported.stream().filter(a -> a.getName().equals("Triathlon")).findFirst().orElseThrow();

			assertThat(importedChild.getId()).isNotEqualTo(child.getId());
			assertThat(importedChild.getParentActivity()).isNotNull();
			assertThat(importedChild.getParentActivity().getId()).isEqualTo(importedParent.getId());
			assertThat(importedChild.getWorkout()).isNotNull();
			assertThat(importedChild.getWorkout().getId()).isNotEqualTo(workout.getId());
			assertThat(importedChild.getShoe()).isNotNull();
			assertThat(importedChild.getShoe().getId()).isNotEqualTo(shoe.getId());

			assertThat(lapRepository.findByActivityIdOrderByIndex(importedChild.getId())).hasSize(1);
			assertThat(recordRepository.findByActivityIdOrderByT(importedChild.getId())).hasSize(1);

			ActivityResponse importedChildResponse = activityService.toResponse(importedChild);
			assertThat(importedChildResponse.tags()).containsExactly("race");

			List<Race> importedRaces = raceRepository.findByAthleteIdOrderByDateAsc(target.getId());
			assertThat(importedRaces).hasSize(1);
			assertThat(importedRaces.get(0).getActivity().getId()).isEqualTo(importedChild.getId());

			List<Workout> importedWorkouts = workoutRepository.findByCreatedByIdOrderByIdDesc(target.getId());
			assertThat(importedWorkouts).hasSize(1);

			List<ScheduledWorkout> importedScheduled = scheduledWorkoutRepository.findByAthleteIdOrderByDate(target.getId());
			assertThat(importedScheduled).hasSize(1);
			assertThat(importedScheduled.get(0).getActivity().getId()).isEqualTo(importedChild.getId());
			assertThat(importedScheduled.get(0).getWorkout().getId()).isEqualTo(importedWorkouts.get(0).getId());
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void readCallsOnStepForEverySectionInOrderEvenWithNoData() throws Exception {
		User source = new User();
		source.setEmail("import-progress-steps-source@example.cc");
		source.setName("Source");
		source.setPassword("irrelevant-for-this-test");
		source = userRepository.save(source);

		User target = new User();
		target.setEmail("import-progress-steps-target@example.cc");
		target.setName("Target");
		target.setPassword("irrelevant-for-this-test");
		target = userRepository.save(target);

		Path file = Files.createTempFile("import-progress-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			List<String> seen = new ArrayList<>();
			importReader.read(target.getId(), file, seen::add);

			assertThat(seen).containsExactly("equipment", "workouts", "activities", "races", "scheduled_workouts");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void readCallsOnTotalAndOnProgressReachingTheFullItemCount() throws Exception {
		// 1 activity, 1 race, 1 workout, 1 scheduled_workout, 1 bike, 1 shoe, 1 component = 7,
		// read straight from the source file's own "counts" metadata block (see ExportWriter).
		User source = newUser("import-progress-items-source@example.cc");
		User target = newUser("import-progress-items-target@example.cc");

		Bike bike = new Bike();
		bike.setAthlete(source);
		bike.setName("Road bike");
		bike.setKind(BikeKind.ROAD);
		bike = bikeRepository.save(bike);

		Component component = new Component();
		component.setBike(bike);
		component.setName("Chain");
		componentRepository.save(component);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("ImportProgressCo");
		shoeModel.setModel("Roundtrip");
		shoeModel = shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion("v1");
		smv = shoeModelVersionRepository.save(smv);
		Shoe shoe = new Shoe();
		shoe.setAthlete(source);
		shoe.setShoeModelVersion(smv);
		shoe.setName("Daily trainer");
		shoeRepository.save(shoe);

		Workout workout = new Workout();
		workout.setCreatedBy(source);
		workout.setName("Tempo Run");
		workout.setSport(Sport.RUN);
		workout = workoutRepository.save(workout);

		Activity activity = new Activity();
		activity.setAthlete(source);
		activity.setSport(Sport.RUN);
		activity.setName("Morning Run");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity = activityRepository.save(activity);

		Race race = new Race();
		race.setAthlete(source);
		race.setName("Local 10k");
		race.setDate(LocalDate.of(2026, 1, 1));
		race.setActivity(activity);
		raceRepository.save(race);

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(workout);
		scheduled.setAthlete(source);
		scheduled.setDate(LocalDate.of(2026, 1, 1));
		scheduled.setActivity(activity);
		scheduledWorkoutRepository.save(scheduled);

		Path file = Files.createTempFile("import-progress-items-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			// onTotal/onProgress are scoped to the *current* section, not a blend across all of
			// them - equipment (bike+shoe+component) totals 3, every other section totals 1.
			List<String> events = new ArrayList<>();
			importReader.read(target.getId(), file, step -> events.add("step:" + step),
					total -> events.add("total:" + total), processed -> events.add("progress:" + processed));

			assertThat(events).containsExactly(
					"step:equipment", "total:3", "progress:3",
					"step:workouts", "total:1", "progress:1",
					"step:activities", "total:1", "progress:1",
					"step:races", "total:1", "progress:1",
					"step:scheduled_workouts", "total:1", "progress:1");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void readSkipsOnTotalForAFileWithNoCountsBlock() throws Exception {
		// A file exported before the "counts" field existed - onTotal should simply never fire
		// rather than crash or report a bogus 0.
		User target = newUser("import-progress-no-counts-target@example.cc");

		Path file = Files.createTempFile("import-progress-no-counts-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				generator.writeStartObject();
				generator.writeStringProperty("generated_at", Instant.now().toString());
				generator.writeStringProperty("athlete_id", "usr_doesnotmatter");
				generator.writeObjectPropertyStart("equipment");
				generator.writeArrayPropertyStart("bikes");
				generator.writeEndArray();
				generator.writeArrayPropertyStart("shoes");
				generator.writeEndArray();
				generator.writeArrayPropertyStart("components");
				generator.writeEndArray();
				generator.writeEndObject();
				generator.writeArrayPropertyStart("workouts");
				generator.writeEndArray();
				generator.writeArrayPropertyStart("activities");
				generator.writeEndArray();
				generator.writeArrayPropertyStart("races");
				generator.writeEndArray();
				generator.writeArrayPropertyStart("scheduled_workouts");
				generator.writeEndArray();
				generator.writeEndObject();
			}

			List<Integer> totals = new ArrayList<>();
			importReader.read(target.getId(), file, step -> { }, totals::add, processed -> { });

			assertThat(totals).isEmpty();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void importedActivityFallsBackToTheImportingAthletesCurrentProfile() throws Exception {
		// ExportWriter doesn't write ftpSnapshot/etc. into the file yet (that lands with
		// threshold-increase detection) - until then, ImportReader falls back to the *importing*
		// athlete's current profile, the same graceful-degradation shape already used for the
		// "counts" progress metadata.
		User source = newUser("snapshot-fallback-source@example.cc");
		User target = newUser("snapshot-fallback-target@example.cc");
		target.setFtp(222);
		target = userRepository.save(target);

		Activity ride = new Activity();
		ride.setAthlete(source);
		ride.setSport(Sport.BIKE);
		ride.setName("Ride");
		ride.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activityRepository.save(ride);

		Path file = Files.createTempFile("import-snapshot-fallback-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			importReader.read(target.getId(), file);

			Activity imported = activityRepository.findByAthleteIdOrderByStartDate(target.getId()).get(0);
			assertThat(imported.getFtpSnapshot()).isEqualTo(222);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}
}
