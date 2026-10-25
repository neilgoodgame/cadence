package com.cadence.api.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.mcp.dto.McpAthleteProfile;
import com.cadence.api.mcp.dto.McpCustomSplitStepInput;
import com.cadence.api.mcp.dto.McpWorkoutLeafStep;
import com.cadence.api.mcp.dto.McpWorkoutStepInput;
import com.cadence.api.mcp.tools.activities.ActivityReadTools;
import com.cadence.api.mcp.tools.athletes.AthleteProfileTools;
import com.cadence.api.mcp.tools.gear.GearReadTools;
import com.cadence.api.mcp.tools.gear.GearWriteTools;
import com.cadence.api.mcp.tools.races.RaceTools;
import com.cadence.api.mcp.tools.scheduling.ScheduledWorkoutWriteTools;
import com.cadence.api.mcp.tools.workouts.WorkoutReadTools;
import com.cadence.api.mcp.tools.workouts.WorkoutWriteTools;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.races.dto.SplitsResponse;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.WorkoutTag;
import com.cadence.api.workouts.WorkoutTagRepository;
import com.cadence.api.workouts.dto.WorkoutMatchComparisonResponse;
import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutTagAttachResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Covers what the live manual verification during development already proved works end-to-end
 * through the real {@code /mcp} transport, but as a durable regression guard: tool outputs
 * persist correctly, {@link com.cadence.api.mcp.dispatch.McpToolAuthorizer} actually rejects a
 * missing scope, and cross-athlete access is still rejected through the MCP path exactly as it
 * is through REST (see {@code WorkoutLibraryIntegrationTest.folderFromAnotherAthleteCannotBeAssigned}
 * for the REST-side equivalent this mirrors).
 */
class McpToolsIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutWriteTools workoutWriteTools;

	@Autowired
	private WorkoutReadTools workoutReadTools;

	@Autowired
	private ScheduledWorkoutWriteTools scheduledWorkoutWriteTools;

	@Autowired
	private AthleteProfileTools athleteProfileTools;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private WorkoutTagRepository workoutTagRepository;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private ActivityReadTools activityReadTools;

	@Autowired
	private RaceTools raceTools;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private RaceRepository raceRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

	@Autowired
	private GearReadTools gearReadTools;

	@Autowired
	private GearWriteTools gearWriteTools;

	@Autowired
	private BikeRepository bikeRepository;

	@Autowired
	private ShoeRepository shoeRepository;

	@Autowired
	private ShoeModelRepository shoeModelRepository;

	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User saveAthlete(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Test Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		return userRepository.save(athlete);
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.OAUTH2));
	}

	private ShoeModelVersion saveShoeModelVersion(String manufacturer, String model, String version) {
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(manufacturer);
		shoeModel.setModel(model);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion(version);
		return shoeModelVersionRepository.save(smv);
	}

	@Test
	void createWorkoutPersistsWithCorrectDurationAndUnrolledRepeats() {
		User athlete = saveAthlete("mcp-create-workout@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutLeafStep> repeatChildren = List.of(
				new McpWorkoutLeafStep("block", "time", 180, null, "power", 105.0, 105.0, null),
				new McpWorkoutLeafStep("rec", "time", 120, null, "power", 55.0, 55.0, null));
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 50.0, 50.0, null, null, null),
				new McpWorkoutStepInput("repeat", null, null, null, null, null, null, 3, null, repeatChildren),
				new McpWorkoutStepInput("cool", "time", 300, null, "power", 45.0, 45.0, null, null, null));

		WorkoutResponse response = workoutWriteTools.createWorkout("MCP Test", "bike", steps, null);

		assertThat(response.duration()).isEqualTo(600 + 3 * (180 + 120) + 300);
		Workout persisted = workoutRepository.findById(response.id()).orElseThrow();
		assertThat(persisted.getCreatedBy().getId()).isEqualTo(athlete.getId());
		// warmup + 3x(block+rec) + cool = 8 leaf points, matching the flattened/unrolled chart preview.
		assertThat(persisted.getChartPreview()).hasSize(8);
	}

	@Test
	void createWorkoutParsesDistanceUnitsIntoMeters() {
		User athlete = saveAthlete("mcp-create-workout-distance@example.cc");
		authAs(athlete.getId(), "workouts:write", "activities:read");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("block", "distance", null, "400m", "pace", 100.0, 100.0, null, null, null),
				new McpWorkoutStepInput("block", "distance", null, "5km", "pace", 100.0, 100.0, null, null, null),
				new McpWorkoutStepInput("block", "distance", null, "3.1mi", "pace", 100.0, 100.0, null, null, null));

		WorkoutResponse response = workoutWriteTools.createWorkout("Distance units", "run", steps, null);

		var persistedSteps = workoutReadTools.getWorkout(response.id()).steps();
		assertThat(persistedSteps.get(0).distance()).isEqualTo(400);
		assertThat(persistedSteps.get(1).distance()).isEqualTo(5000);
		assertThat(persistedSteps.get(2).distance()).isEqualTo(4989); // 3.1 * 1609.344, rounded
	}

	@Test
	void createWorkoutRejectsADistanceWithNoUnit() {
		User athlete = saveAthlete("mcp-create-workout-distance-nounit@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("block", "distance", null, "400", "pace", 100.0, 100.0, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Bad", "run", steps, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createWorkoutRejectsAnInvalidTargetType() {
		User athlete = saveAthlete("mcp-create-workout-invalid@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "nonsense", 50.0, 50.0, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Bad", "bike", steps, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createWorkoutRejectsAFractionPassedInsteadOfAPercentage() {
		User athlete = saveAthlete("mcp-create-workout-fraction@example.cc");
		authAs(athlete.getId(), "workouts:write");

		// The exact mistake seen live: 0.65 instead of 65 for 65% of threshold.
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 0.65, 0.75, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Bad", "bike", steps, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createWorkoutRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-create-workout-noscope@example.cc");
		authAs(athlete.getId()); // no scopes granted at all

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 50.0, 50.0, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.createWorkout("Should Not Persist", "bike", steps, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void updateWorkoutNameOnlyLeavesStepsAndTagsUntouched() {
		User athlete = saveAthlete("mcp-update-workout@example.cc");
		authAs(athlete.getId(), "workouts:write", "activities:read");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Original Name", "bike", steps, List.of("a"));

		WorkoutResponse updated = workoutWriteTools.updateWorkout(created.id(), "Renamed", null, null);

		assertThat(updated.name()).isEqualTo("Renamed");
		assertThat(updated.tags()).containsExactly("a");
		assertThat(workoutReadTools.getWorkout(created.id()).steps()).hasSize(1);
	}

	@Test
	void updateWorkoutStepsReplacesTreeAndRecomputesDuration() {
		User athlete = saveAthlete("mcp-update-workout-steps@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutStepInput> originalSteps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("To Edit", "bike", originalSteps, null);

		List<McpWorkoutStepInput> newSteps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 50.0, 50.0, null, null, null),
				new McpWorkoutStepInput("cool", "time", 300, null, "power", 45.0, 45.0, null, null, null));
		WorkoutResponse updated = workoutWriteTools.updateWorkout(created.id(), null, newSteps, null);

		assertThat(updated.duration()).isEqualTo(900);
	}

	@Test
	void updateWorkoutRejectsAFractionPassedInsteadOfAPercentage() {
		User athlete = saveAthlete("mcp-update-workout-fraction@example.cc");
		authAs(athlete.getId(), "workouts:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("To Edit", "bike", steps, null);

		List<McpWorkoutStepInput> badSteps = List.of(
				new McpWorkoutStepInput("warmup", "time", 600, null, "power", 0.65, 0.75, null, null, null));

		assertThatThrownBy(() -> workoutWriteTools.updateWorkout(created.id(), null, badSteps, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void updateWorkoutRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-update-workout-noscope@example.cc");
		authAs(athlete.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("To Edit", "bike", steps, null);

		authAs(athlete.getId()); // no scopes granted at all
		assertThatThrownBy(() -> workoutWriteTools.updateWorkout(created.id(), "Renamed", null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void updateWorkoutRejectsAnotherAthletesWorkout() {
		User owner = saveAthlete("mcp-update-workout-owner@example.cc");
		authAs(owner.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-update-workout-outsider@example.cc");
		authAs(outsider.getId(), "workouts:write");
		assertThatThrownBy(() -> workoutWriteTools.updateWorkout(created.id(), "Hijacked", null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void deleteWorkoutRemovesIt() {
		User athlete = saveAthlete("mcp-delete-workout@example.cc");
		authAs(athlete.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Doomed", "bike", steps, null);

		workoutWriteTools.deleteWorkout(created.id());

		assertThat(workoutRepository.findById(created.id())).isEmpty();
	}

	@Test
	void deleteWorkoutRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-delete-workout-noscope@example.cc");
		authAs(athlete.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Should Survive", "bike", steps, null);

		authAs(athlete.getId()); // no scopes granted at all
		assertThatThrownBy(() -> workoutWriteTools.deleteWorkout(created.id())).isInstanceOf(ForbiddenException.class);
		assertThat(workoutRepository.findById(created.id())).isPresent();
	}

	@Test
	void tagWorkoutAttachesANewTag() {
		User athlete = saveAthlete("mcp-tag-workout@example.cc");
		authAs(athlete.getId(), "workouts:write", "activities:read");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Long Run", "run", steps, null);

		WorkoutTagAttachResponse result = workoutWriteTools.tagWorkout(created.id(), "Key session");

		assertThat(result.workoutId()).isEqualTo(created.id());
		assertThat(result.tag().name()).isEqualTo("Key session");
		assertThat(workoutReadTools.getWorkout(created.id()).workout().tags()).containsExactly("Key session");
	}

	@Test
	void tagWorkoutSharesTheActivityTagCatalog() {
		User athlete = saveAthlete("mcp-tag-workout-shared@example.cc");
		authAs(athlete.getId(), "workouts:write");
		Tag existing = new Tag();
		existing.setAthlete(athlete);
		existing.setName("Key session");
		existing.setOrigin(TagOrigin.MANUAL);
		existing = tagRepository.save(existing);
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Long Run", "run", steps, null);

		WorkoutTagAttachResponse result = workoutWriteTools.tagWorkout(created.id(), "Key session");

		assertThat(result.tag().id()).isEqualTo(existing.getId());
	}

	@Test
	void tagWorkoutRejectsAnOutsider() {
		User owner = saveAthlete("mcp-tag-workout-owner@example.cc");
		authAs(owner.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-tag-workout-outsider@example.cc");
		authAs(outsider.getId(), "workouts:write");
		assertThatThrownBy(() -> workoutWriteTools.tagWorkout(created.id(), "Key session"))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void untagWorkoutRemovesIt() {
		User athlete = saveAthlete("mcp-untag-workout@example.cc");
		authAs(athlete.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Long Run", "run", steps, null);
		WorkoutTagAttachResponse tagged = workoutWriteTools.tagWorkout(created.id(), "Key session");

		WorkoutResponse result = workoutWriteTools.untagWorkout(created.id(), tagged.tag().id());

		assertThat(result.tags()).isEmpty();
	}

	@Test
	void untagWorkoutRejectsAnAutoAppliedTag() {
		User athlete = saveAthlete("mcp-untag-workout-auto@example.cc");
		authAs(athlete.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Long Run", "run", steps, null);
		Tag newTag = new Tag();
		newTag.setAthlete(athlete);
		newTag.setName("Auto-matched");
		newTag.setOrigin(TagOrigin.AUTO);
		Tag autoTag = tagRepository.save(newTag);
		WorkoutTag link = new WorkoutTag();
		link.setWorkout(workoutRepository.findById(created.id()).orElseThrow());
		link.setTag(autoTag);
		workoutTagRepository.save(link);

		assertThatThrownBy(() -> workoutWriteTools.untagWorkout(created.id(), autoTag.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void deleteWorkoutRejectsAnotherAthletesWorkout() {
		User owner = saveAthlete("mcp-delete-workout-owner@example.cc");
		authAs(owner.getId(), "workouts:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse created = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-delete-workout-outsider@example.cc");
		authAs(outsider.getId(), "workouts:write");
		assertThatThrownBy(() -> workoutWriteTools.deleteWorkout(created.id())).isInstanceOf(ForbiddenException.class);
		assertThat(workoutRepository.findById(created.id())).isPresent();
	}

	@Test
	void scheduleWorkoutPersists() {
		User athlete = saveAthlete("mcp-schedule@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");

		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Schedule", "bike", steps, null);

		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 1), "am", "Race sim");

		assertThat(scheduled.workoutId()).isEqualTo(workout.id());
		assertThat(scheduled.athleteId()).isEqualTo(athlete.getId());
		assertThat(scheduled.date()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(scheduled.notes()).isEqualTo("Race sim");
	}

	// Regression test for a real bug found live: Claude.ai's MCP connector reported having no way
	// to move a scheduled workout, so a requested "swap two dates" left duplicate entries instead
	// of a clean move - see move_workout's Javadoc.
	@Test
	void moveWorkoutChangesTheDateOfAnExistingEntryInPlace() {
		User athlete = saveAthlete("mcp-move@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Move", "bike", steps, null);
		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 6), "am", null);

		ScheduledWorkoutResponse moved = scheduledWorkoutWriteTools.moveWorkout(
				scheduled.id(), LocalDate.of(2026, 9, 13), "pm", null);

		assertThat(moved.id()).isEqualTo(scheduled.id());
		assertThat(moved.date()).isEqualTo(LocalDate.of(2026, 9, 13));
		assertThat(moved.timeOfDay().name()).isEqualToIgnoringCase("pm");
	}

	// Follow-up to the move_workout fix above: also lets a caller attach a note to an entry
	// without needing to touch its date, e.g. adding context to an existing swap.
	@Test
	void moveWorkoutCanUpdateJustTheNotes() {
		User athlete = saveAthlete("mcp-move-notes@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Annotate", "bike", steps, null);
		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 6), "am", null);

		ScheduledWorkoutResponse annotated = scheduledWorkoutWriteTools.moveWorkout(
				scheduled.id(), null, null, "Swap if it rains");

		assertThat(annotated.date()).isEqualTo(LocalDate.of(2026, 9, 6));
		assertThat(annotated.notes()).isEqualTo("Swap if it rains");
	}

	@Test
	void unscheduleWorkoutRemovesTheEntry() {
		User athlete = saveAthlete("mcp-unschedule@example.cc");
		authAs(athlete.getId(), "workouts:write", "calendar:write");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("To Unschedule", "bike", steps, null);
		ScheduledWorkoutResponse scheduled = scheduledWorkoutWriteTools.scheduleWorkout(
				workout.id(), LocalDate.of(2026, 9, 6), "am", null);

		var result = scheduledWorkoutWriteTools.unscheduleWorkout(scheduled.id());

		assertThat(result.get("deleted")).isEqualTo(true);
		assertThatThrownBy(() -> scheduledWorkoutWriteTools.moveWorkout(scheduled.id(), LocalDate.of(2026, 9, 7), null, null))
				.isInstanceOf(com.cadence.api.common.error.NotFoundException.class);
	}

	@Test
	void getWorkoutFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-workout-owner@example.cc");
		authAs(owner.getId(), "workouts:write", "activities:read");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-workout-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> workoutReadTools.getWorkout(workout.id())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getWorkoutMatchesReturnsComparisonDataForEachMatchedActivity() {
		User athlete = saveAthlete("mcp-workout-matches@example.cc");
		authAs(athlete.getId(), "workouts:write", "activities:read");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("Matched workout", "bike", steps, null);
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(Instant.now());
		activity.setMovingTime(1200);
		activity.setDistanceKm(35.0);
		activity.setAvgPower(210);
		activity.setAvgHr(140);
		activity.setTss(33);
		activity.setWorkout(workoutRepository.findById(workout.id()).orElseThrow());
		activityRepository.save(activity);

		List<WorkoutMatchComparisonResponse> matches = workoutReadTools.getWorkoutMatches(workout.id());

		assertThat(matches).hasSize(1);
		WorkoutMatchComparisonResponse row = matches.get(0);
		assertThat(row.activityId()).isEqualTo(activity.getId());
		assertThat(row.ef()).isCloseTo(210.0 / 140, org.assertj.core.data.Offset.offset(0.001));
	}

	@Test
	void getWorkoutMatchesFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-workout-matches-owner@example.cc");
		authAs(owner.getId(), "workouts:write", "activities:read");
		List<McpWorkoutStepInput> steps = List.of(
				new McpWorkoutStepInput("warmup", "time", 300, null, "power", 50.0, 50.0, null, null, null));
		WorkoutResponse workout = workoutWriteTools.createWorkout("Owner's workout", "bike", steps, null);

		User outsider = saveAthlete("mcp-workout-matches-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");

		assertThatThrownBy(() -> workoutReadTools.getWorkoutMatches(workout.id())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getMeReturnsTheCallersOwnProfile() {
		User athlete = saveAthlete("mcp-get-me@example.cc");
		athlete.setFtp(250);
		userRepository.save(athlete);
		authAs(athlete.getId(), "activities:read");

		McpAthleteProfile profile = athleteProfileTools.getMe();

		assertThat(profile.id()).isEqualTo(athlete.getId());
		assertThat(profile.ftp()).isEqualTo(250);
	}

	@Test
	void getMeForADelegatedCoachReturnsTheAthletesProfileNotTheCoachsOwn() {
		User athlete = saveAthlete("mcp-get-me-athlete@example.cc");
		athlete.setFtp(250);
		userRepository.save(athlete);
		User coach = saveAthlete("mcp-get-me-coach@example.cc");
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);
		AuthContextHolder.set(
				new AuthContext(coach.getId(), athlete.getId(), Set.of("activities:read"), AuthContext.CredentialKind.PERSONAL_ACCESS_TOKEN));

		McpAthleteProfile profile = athleteProfileTools.getMe();

		assertThat(profile.id()).isEqualTo(athlete.getId());
		assertThat(profile.ftp()).isEqualTo(250);
	}

	@Test
	void getActivityCustomSplitsParsesAMileStepAndRejectsOtherAthletesActivity() {
		User athlete = saveAthlete("mcp-act-custom-splits@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Easy run");
		activity.setStartDate(Instant.parse("2026-10-12T08:00:00Z"));
		activity.setDistanceKm(5.0);
		activity.setMovingTime(1500);
		activityRepository.save(activity);
		for (int t = 0; t <= 1500; t += 10) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round(t / 300.0 * 10000) / 10000.0);
			recordRepository.save(record);
		}
		authAs(athlete.getId(), "activities:read");

		ActivitySplitsResponse result = activityReadTools.getActivityCustomSplits(
				activity.getId(), List.of(new McpCustomSplitStepInput("distance", null, "1mi", "Mile 1")));

		assertThat(result.splits().get(0).label()).isEqualTo("Mile 1");
		assertThat(result.splits().get(0).endKm()).isCloseTo(1.609, org.assertj.core.api.Assertions.within(0.01));

		User outsider = saveAthlete("mcp-act-custom-splits-outsider@example.cc");
		authAs(outsider.getId(), "activities:read");
		assertThatThrownBy(() -> activityReadTools.getActivityCustomSplits(
				activity.getId(), List.of(new McpCustomSplitStepInput("time", 60, null, null))))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getRaceCustomSplitsUsesTheRacesOwnGoalPace() {
		User athlete = saveAthlete("mcp-race-custom-splits@example.cc");
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("5k time trial");
		activity.setStartDate(Instant.parse("2026-10-12T08:00:00Z"));
		activity.setDistanceKm(5.0);
		activity.setMovingTime(1500);
		activityRepository.save(activity);
		for (int t = 0; t <= 1500; t += 10) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setDistanceKm(Math.round(t / 300.0 * 10000) / 10000.0);
			recordRepository.save(record);
		}
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("5k");
		race.setDate(LocalDate.of(2026, 10, 12));
		race.setActivity(activity);
		race.setDistanceKm(5.0);
		race.setGoalTime(25 * 60); // exactly 300s/km goal pace
		raceRepository.save(race);
		authAs(athlete.getId(), "activities:read");

		Object result = raceTools.getRaceCustomSplits(
				race.getId(), List.of(new McpCustomSplitStepInput("distance", null, "1km", null)));

		assertThat(result).isInstanceOf(SplitsResponse.class);
		SplitsResponse response = (SplitsResponse) result;
		assertThat(response.ref()).isEqualTo("goal");
		assertThat(response.splits().get(0).deltaVsRefSPerKm())
				.isCloseTo(0.0, org.assertj.core.api.Assertions.within(0.5));
	}

	@Test
	void createRacePersistsWithNoBlockPlan() {
		User athlete = saveAthlete("mcp-create-race@example.cc");
		authAs(athlete.getId(), "calendar:write");

		RaceResponse response = raceTools.createRace("Goal Marathon", "2026-10-12", "run", null, 42.195, null, null,
				null, null, null, null, null, null, null, null, null);

		assertThat(response.name()).isEqualTo("Goal Marathon");
		assertThat(response.priority()).isEqualTo("A");
		assertThat(response.blockWeeks()).isEqualTo(16);
		assertThat(response.blockWeeksRequested()).isNull();
	}

	@Test
	void createRaceExplicitBlockPlanRoundTrips() {
		User athlete = saveAthlete("mcp-create-race-explicit@example.cc");
		authAs(athlete.getId(), "calendar:write");

		RaceResponse response = raceTools.createRace("Goal Marathon", "2026-10-12", "run", null, null, null, null,
				null, null, null, null, 20, 8, 7, 2, 3);

		assertThat(response.blockWeeksRequested()).isEqualTo(20);
		assertThat(response.blockWeeks()).isEqualTo(20);
		Race persisted = raceRepository.findById(response.id()).orElseThrow();
		assertThat(persisted.getBaseWeeksRequested()).isEqualTo(8);
	}

	@Test
	void createRacePhaseWeeksNotSummingToBlockWeeksAreRejected() {
		User athlete = saveAthlete("mcp-create-race-sum@example.cc");
		authAs(athlete.getId(), "calendar:write");

		assertThatThrownBy(() -> raceTools.createRace("Goal Marathon", "2026-10-12", "run", null, null, null, null,
				null, null, null, null, 20, 5, 5, 2, 3))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createRaceBlockPlanFieldsAreRejectedOnABcRace() {
		User athlete = saveAthlete("mcp-create-race-bc@example.cc");
		authAs(athlete.getId(), "calendar:write");

		assertThatThrownBy(() -> raceTools.createRace("Tune-up Half", "2026-09-14", "run", "B", null, null, null,
				null, null, null, null, 10, null, null, null, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createRaceRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-create-race-scope@example.cc");
		authAs(athlete.getId(), "activities:read");

		assertThatThrownBy(() -> raceTools.createRace("Goal Marathon", "2026-10-12", "run", null, null, null, null,
				null, null, null, null, null, null, null, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void createBikePersistsWithDefaults() {
		User athlete = saveAthlete("mcp-gear-create-bike@example.cc");
		authAs(athlete.getId(), "gear:write");

		BikeResponse response = gearWriteTools.createBike("Tarmac SL7", "road", "Dura-Ace", 1200);

		assertThat(response.name()).isEqualTo("Tarmac SL7");
		assertThat(response.distanceKm()).isEqualTo(1200);
		Bike persisted = bikeRepository.findById(response.id()).orElseThrow();
		assertThat(persisted.getAthlete().getId()).isEqualTo(athlete.getId());
	}

	@Test
	void createBikeRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-gear-create-bike-scope@example.cc");
		authAs(athlete.getId(), "activities:read");

		assertThatThrownBy(() -> gearWriteTools.createBike("Tarmac SL7", null, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void updateBikeChangesOnlyPassedFields() {
		User athlete = saveAthlete("mcp-gear-update-bike@example.cc");
		authAs(athlete.getId(), "gear:write");
		BikeResponse created = gearWriteTools.createBike("Original", "road", null, null);

		BikeResponse updated = gearWriteTools.updateBike(created.id(), "Renamed", null, null, 500);

		assertThat(updated.name()).isEqualTo("Renamed");
		assertThat(updated.kind().wireValue()).isEqualTo("road");
		assertThat(updated.distanceKm()).isEqualTo(500);
	}

	@Test
	void updateBikeFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-gear-update-bike-owner@example.cc");
		User other = saveAthlete("mcp-gear-update-bike-other@example.cc");
		authAs(owner.getId(), "gear:write");
		BikeResponse created = gearWriteTools.createBike("Tarmac SL7", null, null, null);

		authAs(other.getId(), "gear:write");
		assertThatThrownBy(() -> gearWriteTools.updateBike(created.id(), "Hijacked", null, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void deleteBikeRemovesIt() {
		User athlete = saveAthlete("mcp-gear-delete-bike@example.cc");
		authAs(athlete.getId(), "gear:write");
		BikeResponse created = gearWriteTools.createBike("Doomed", null, null, null);

		gearWriteTools.deleteBike(created.id());

		assertThat(bikeRepository.findById(created.id())).isEmpty();
	}

	@Test
	void deleteBikeFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-gear-delete-bike-owner@example.cc");
		User other = saveAthlete("mcp-gear-delete-bike-other@example.cc");
		authAs(owner.getId(), "gear:write");
		BikeResponse created = gearWriteTools.createBike("Tarmac SL7", null, null, null);

		authAs(other.getId(), "gear:write");
		assertThatThrownBy(() -> gearWriteTools.deleteBike(created.id())).isInstanceOf(ForbiddenException.class);
		assertThat(bikeRepository.findById(created.id())).isPresent();
	}

	@Test
	void searchShoeCatalogFiltersByManufacturerOrModel() {
		// Fake, distinctive manufacturer names - the real shoe catalog is seeded with real
		// brands by a Flyway migration, so a count-based assertion must avoid colliding with
		// that pre-existing seeded data.
		User athlete = saveAthlete("mcp-gear-search-catalog@example.cc");
		authAs(athlete.getId(), "activities:read");
		saveShoeModelVersion("Zzyzx", "Vaporfly", "3");
		saveShoeModelVersion("Qorvex", "Nimbus", "25");

		List<ShoeCatalogEntryResponse> results = gearReadTools.searchShoeCatalog("zzyzx");

		assertThat(results).hasSize(1);
		assertThat(results.get(0).manufacturer()).isEqualTo("Zzyzx");
	}

	@Test
	void createShoePersistsAndDefaultsName() {
		User athlete = saveAthlete("mcp-gear-create-shoe@example.cc");
		authAs(athlete.getId(), "gear:write");
		ShoeModelVersion smv = saveShoeModelVersion("Nike", "Vaporfly", "3");

		ShoeResponse response = gearWriteTools.createShoe(smv.getId(), "Volt / Black", null, null, null);

		assertThat(response.name()).isEqualTo("Nike Vaporfly 3 Volt / Black");
		Shoe persisted = shoeRepository.findById(response.id()).orElseThrow();
		assertThat(persisted.getAthlete().getId()).isEqualTo(athlete.getId());
	}

	@Test
	void createShoeRejectsWithoutTheWriteScope() {
		User athlete = saveAthlete("mcp-gear-create-shoe-scope@example.cc");
		authAs(athlete.getId(), "activities:read");
		ShoeModelVersion smv = saveShoeModelVersion("Nike", "Vaporfly", "3");

		assertThatThrownBy(() -> gearWriteTools.createShoe(smv.getId(), "Volt", null, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void updateShoeRetiresIt() {
		User athlete = saveAthlete("mcp-gear-update-shoe@example.cc");
		authAs(athlete.getId(), "gear:write");
		ShoeModelVersion smv = saveShoeModelVersion("Nike", "Vaporfly", "3");
		ShoeResponse created = gearWriteTools.createShoe(smv.getId(), "Volt", "Race Flyers", null, null);

		gearWriteTools.updateShoe(created.id(), null, null, null, null, true);

		Shoe persisted = shoeRepository.findById(created.id()).orElseThrow();
		assertThat(persisted.isRetired()).isTrue();
	}

	@Test
	void updateShoeFromAnotherAthleteIsRejected() {
		User owner = saveAthlete("mcp-gear-update-shoe-owner@example.cc");
		User other = saveAthlete("mcp-gear-update-shoe-other@example.cc");
		authAs(owner.getId(), "gear:write");
		ShoeModelVersion smv = saveShoeModelVersion("Nike", "Vaporfly", "3");
		ShoeResponse created = gearWriteTools.createShoe(smv.getId(), "Volt", null, null, null);

		authAs(other.getId(), "gear:write");
		assertThatThrownBy(() -> gearWriteTools.updateShoe(created.id(), "Hijacked", null, null, null, null))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void deleteShoeRemovesIt() {
		User athlete = saveAthlete("mcp-gear-delete-shoe@example.cc");
		authAs(athlete.getId(), "gear:write");
		ShoeModelVersion smv = saveShoeModelVersion("Nike", "Vaporfly", "3");
		ShoeResponse created = gearWriteTools.createShoe(smv.getId(), "Volt", null, null, null);

		gearWriteTools.deleteShoe(created.id());

		assertThat(shoeRepository.findById(created.id())).isEmpty();
	}

	@Test
	void createShoeCatalogEntryPersists() {
		User athlete = saveAthlete("mcp-gear-create-catalog@example.cc");
		authAs(athlete.getId(), "gear:write");

		ShoeCatalogEntryResponse response = gearWriteTools.createShoeCatalogEntry("Hoka", "Rocket X", "2");

		assertThat(response.manufacturer()).isEqualTo("Hoka");
		assertThat(response.version()).isEqualTo("2");
		assertThat(shoeModelRepository.findFirstByManufacturerIgnoreCaseAndModelIgnoreCase("Hoka", "Rocket X"))
				.isPresent();
	}
}
