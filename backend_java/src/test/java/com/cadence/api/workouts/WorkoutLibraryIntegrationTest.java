package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutFolderResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkoutLibraryIntegrationTest extends IntegrationTest {

	@Autowired
	private WorkoutService workoutService;

	@Autowired
	private WorkoutFolderService workoutFolderService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ScheduledWorkoutRepository scheduledWorkoutRepository;

	private static WorkoutStepDto leaf(StepKind kind, StepEndType endType, Integer duration, TargetType targetType,
			Double low, Double high) {
		return new WorkoutStepDto(kind, endType, duration, null, targetType, low, high, Target2Type.NONE, null, null,
				1, "", List.of());
	}

	private User saveAthlete(String email) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Test Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		return userRepository.save(athlete);
	}

	private Workout createWorkout(User athlete, String name, Sport sport, String folderId, List<String> tags) {
		List<WorkoutStepDto> steps = List
				.of(leaf(StepKind.BLOCK, StepEndType.TIME, 300, TargetType.POWER, 50.0, 70.0));
		return workoutService.createWorkout(athlete, new WorkoutCreateRequest(name, sport, steps, folderId, tags));
	}

	@Test
	void createAndListFoldersWithCounts() {
		User athlete = saveAthlete("folders-list@example.cc");
		WorkoutFolderResponse folder = workoutFolderService.createFolder(athlete, "VO2 Max");
		assertThat(folder.count()).isEqualTo(0);

		createWorkout(athlete, "VO2 5x5", Sport.BIKE, folder.id(), null);

		List<WorkoutFolderResponse> folders = workoutFolderService.listFolders(athlete.getId());
		assertThat(folders).hasSize(1);
		assertThat(folders.get(0).count()).isEqualTo(1);
	}

	@Test
	void duplicateFolderNameIsRejected() {
		User athlete = saveAthlete("folders-dupe@example.cc");
		workoutFolderService.createFolder(athlete, "Race Prep");

		assertThatThrownBy(() -> workoutFolderService.createFolder(athlete, "Race Prep"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void renameAndDeleteFolderUnassignsWorkouts() {
		User athlete = saveAthlete("folders-rename@example.cc");
		WorkoutFolderResponse folder = workoutFolderService.createFolder(athlete, "Base");
		Workout workout = createWorkout(athlete, "Endurance", Sport.BIKE, folder.id(), null);
		assertThat(workout.getFolder().getId()).isEqualTo(folder.id());

		WorkoutFolder folderEntity = workoutFolderService.getFolder(folder.id());
		WorkoutFolderResponse renamed = workoutFolderService.renameFolder(folderEntity, "Base / Endurance");
		assertThat(renamed.name()).isEqualTo("Base / Endurance");

		workoutFolderService.deleteFolder(folderEntity);
		assertThatThrownBy(() -> workoutFolderService.getFolder(folder.id())).isInstanceOf(NotFoundException.class);

		Workout fetched = workoutService.getWorkout(workout.getId());
		assertThat(fetched.getFolder()).isNull();
	}

	@Test
	void folderFromAnotherAthleteCannotBeAssigned() {
		User athlete = saveAthlete("folder-owner@example.cc");
		User other = saveAthlete("folder-outsider@example.cc");
		WorkoutFolderResponse theirFolder = workoutFolderService.createFolder(other, "Their folder");

		assertThatThrownBy(() -> createWorkout(athlete, "Sneaky", Sport.BIKE, theirFolder.id(), null))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void createAndUpdateWorkoutTags() {
		User athlete = saveAthlete("tags@example.cc");
		Workout workout = createWorkout(athlete, "Tagged", Sport.BIKE, null, List.of("intervals", "race prep"));
		assertThat(workout.getTags()).containsExactly("intervals", "race prep");
	}

	@Test
	void chartPreviewIsComputedOnSave() {
		User athlete = saveAthlete("chart-preview@example.cc");
		Workout workout = createWorkout(athlete, "Chart preview", Sport.BIKE, null, null);
		assertThat(workout.getChartPreview()).containsExactly(60.0);
	}

	@Test
	void listFiltersByFolderTagSportAndSearch() {
		User athlete = saveAthlete("list-filters@example.cc");
		WorkoutFolderResponse folder = workoutFolderService.createFolder(athlete, "Intervals");
		createWorkout(athlete, "VO2 Max 5x5", Sport.BIKE, folder.id(), List.of("hard"));
		createWorkout(athlete, "Easy Spin", Sport.BIKE, null, List.of("easy"));
		createWorkout(athlete, "Long Run", Sport.RUN, null, List.of("easy"));

		assertThat(workoutService.listWorkouts(athlete.getId(), folder.id(), null, null, null, null))
				.extracting(Workout::getName).containsExactly("VO2 Max 5x5");

		assertThat(workoutService.listWorkouts(athlete.getId(), null, "easy", null, null, null))
				.extracting(Workout::getName).containsExactlyInAnyOrder("Easy Spin", "Long Run");

		assertThat(workoutService.listWorkouts(athlete.getId(), null, null, "run", null, null))
				.extracting(Workout::getName).containsExactly("Long Run");

		assertThat(workoutService.listWorkouts(athlete.getId(), null, null, null, "vo2", null))
				.extracting(Workout::getName).containsExactly("VO2 Max 5x5");
	}

	@Test
	void listSortsByNameAndUsage() {
		User athlete = saveAthlete("list-sort@example.cc");
		createWorkout(athlete, "Zulu", Sport.BIKE, null, null);
		Workout alpha = createWorkout(athlete, "Alpha", Sport.BIKE, null, null);

		assertThat(workoutService.listWorkouts(athlete.getId(), null, null, null, null, "name"))
				.extracting(Workout::getName).containsExactly("Alpha", "Zulu");

		ScheduledWorkout scheduled = new ScheduledWorkout();
		scheduled.setWorkout(alpha);
		scheduled.setAthlete(athlete);
		scheduled.setDate(LocalDate.of(2026, 8, 1));
		scheduledWorkoutRepository.save(scheduled);

		assertThat(workoutService.listWorkouts(athlete.getId(), null, null, null, null, "used").get(0).getName())
				.isEqualTo("Alpha");
	}

	@Test
	void invalidSortIsRejected() {
		User athlete = saveAthlete("invalid-sort@example.cc");
		assertThatThrownBy(() -> workoutService.listWorkouts(athlete.getId(), null, null, null, null, "bogus"))
				.isInstanceOf(ValidationException.class);
	}
}
