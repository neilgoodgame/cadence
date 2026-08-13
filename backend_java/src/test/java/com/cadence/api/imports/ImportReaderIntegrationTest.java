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
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryRepository;
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
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

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

		ThresholdHistory thresholdEntry = new ThresholdHistory();
		thresholdEntry.setAthlete(source);
		thresholdEntry.setField(ThresholdField.THRESHOLD_PACE);
		thresholdEntry.setValuePace("4:30");
		thresholdEntry.setSourceActivity(child);
		thresholdEntry.setEffectiveFrom(LocalDate.of(2026, 1, 1));
		thresholdHistoryRepository.save(thresholdEntry);

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
			assertThat(counts.thresholdHistoryImported()).isEqualTo(1);
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

			List<ThresholdHistory> importedThresholdHistory = thresholdHistoryRepository.findBySourceActivityId(importedChild.getId());
			assertThat(importedThresholdHistory).hasSize(1);
			assertThat(importedThresholdHistory.get(0).getField()).isEqualTo(ThresholdField.THRESHOLD_PACE);
			assertThat(importedThresholdHistory.get(0).getValuePace()).isEqualTo("4:30");
			assertThat(importedThresholdHistory.get(0).getAthlete().getId()).isEqualTo(target.getId());
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

			assertThat(seen).containsExactly(
					"equipment", "workouts", "activities", "races", "scheduled_workouts", "threshold_history");
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
					"step:scheduled_workouts", "total:1", "progress:1",
					"step:threshold_history", "total:0", "progress:0");
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
	void importedActivityCarriesOverTheSourcesOwnHistoricalValueUnchanged() throws Exception {
		// threshold_history entries carry the source athlete's own historical values verbatim -
		// imported activities' zones should reflect what was actually true when they happened,
		// not get re-derived against the importing athlete's own (possibly unrelated) fitness.
		User source = newUser("threshold-history-roundtrip-source@example.cc");
		User target = newUser("threshold-history-roundtrip-target@example.cc");
		target.setFtp(222);
		target = userRepository.save(target);

		Activity ride = new Activity();
		ride.setAthlete(source);
		ride.setSport(Sport.BIKE);
		ride.setName("Ride");
		ride.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		ride = activityRepository.save(ride);

		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(source);
		entry.setField(ThresholdField.FTP);
		entry.setValueNumeric(250);
		entry.setSourceActivity(ride);
		entry.setEffectiveFrom(LocalDate.of(2026, 1, 1));
		thresholdHistoryRepository.save(entry);

		Path file = Files.createTempFile("import-threshold-history-roundtrip-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			importReader.read(target.getId(), file);

			Activity importedActivity = activityRepository.findByAthleteIdOrderByStartDate(target.getId()).get(0);
			List<ThresholdHistory> importedEntries = thresholdHistoryRepository.findBySourceActivityId(importedActivity.getId());
			assertThat(importedEntries).hasSize(1);
			assertThat(importedEntries.get(0).getField()).isEqualTo(ThresholdField.FTP);
			assertThat(importedEntries.get(0).getValueNumeric()).isEqualTo(250);

			// The target's own live ftp is untouched by the import - only the ledger is populated.
			User reloadedTarget = userRepository.findById(target.getId()).orElseThrow();
			assertThat(reloadedTarget.getFtp()).isEqualTo(222);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void aManuallyEnteredValueRoundTripsWithNoSourceActivity() throws Exception {
		// sourceActivity=null (see ThresholdHistoryService.recordManualValue) - not a malformed
		// or unmapped reference, so this must NOT be treated as a dangling link and skipped.
		User source = newUser("threshold-manual-roundtrip-source@example.cc");
		User target = newUser("threshold-manual-roundtrip-target@example.cc");

		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(source);
		entry.setField(ThresholdField.FTP);
		entry.setValueNumeric(260);
		entry.setSourceActivity(null);
		entry.setEffectiveFrom(LocalDate.of(2026, 1, 1));
		thresholdHistoryRepository.save(entry);

		Path file = Files.createTempFile("import-threshold-manual-roundtrip-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			ImportCounts counts = importReader.read(target.getId(), file);

			assertThat(counts.thresholdHistoryImported()).isEqualTo(1);
			assertThat(counts.itemsSkipped()).isZero();
			ThresholdHistory imported = thresholdHistoryRepository
					.findByAthleteIdAndFieldOrderByEffectiveFromDesc(target.getId(), ThresholdField.FTP).get(0);
			assertThat(imported.getValueNumeric()).isEqualTo(260);
			assertThat(imported.getSourceActivity()).isNull();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void aDanglingSourceActivityReferenceIsStillSkipped() throws Exception {
		// Unlike a manual entry (sourceActivityId absent from the row entirely), this row HAS a
		// sourceActivityId but no matching activity appears anywhere in the file - genuinely
		// unmappable (e.g. that specific activity failed to import), must still be skipped
		// rather than silently imported as a "manual" entry.
		User target = newUser("threshold-dangling-target@example.cc");
		Path file = Files.createTempFile("import-threshold-dangling-test", ".json.gz");
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
				generator.writeArrayPropertyStart("threshold_history");
				generator.writePOJO(new com.cadence.api.export.dto.ThresholdHistoryExportEntry(
						ThresholdField.FTP, 260, "", "act_doesnotexist", LocalDate.of(2026, 1, 1)));
				generator.writeEndArray();
				generator.writeEndObject();
			}

			ImportCounts counts = importReader.read(target.getId(), file);

			assertThat(counts.thresholdHistoryImported()).isZero();
			assertThat(counts.itemsSkipped()).isEqualTo(1);
			assertThat(thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDesc(
					target.getId(), ThresholdField.FTP)).isEmpty();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void aFileExportedBeforeThisFeatureExistedImportsWithAnEmptyLedger() throws Exception {
		// No "threshold_history" key at all in the source document - the pre-feature shape.
		User target = newUser("threshold-history-pre-feature-target@example.cc");

		Path file = Files.createTempFile("import-threshold-history-pre-feature-test", ".json.gz");
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

			ImportCounts counts = importReader.read(target.getId(), file);
			assertThat(counts.thresholdHistoryImported()).isZero();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}
}
