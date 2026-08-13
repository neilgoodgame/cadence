package com.cadence.api.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Lap;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import com.cadence.api.workouts.dto.InferredWorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Same worked examples as backend/workouts/tests.py - keeps both inference implementations honest against each other. */
class WorkoutInferenceServiceTest {

	// referenceFor() reads straight off the User entity and never touches either repository, so a
	// real ZoneService wired to null repositories is safe to use here.
	private final WorkoutInferenceService service = new WorkoutInferenceService(new ZoneService(null, null));

	private static WorkoutInferenceService.Node.LeafCandidate leaf(int duration, TargetType targetType, Double pct) {
		return new WorkoutInferenceService.Node.LeafCandidate(duration, targetType, pct);
	}

	private static Activity activity(User athlete, Sport sport) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Zwift VO2 Max 5x5");
		activity.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		return activity;
	}

	private static Lap lap(int index, int duration, Integer avgPower, Integer avgHr) {
		Lap lap = new Lap();
		lap.setIndex(index);
		lap.setDuration(duration);
		lap.setDistanceKm(0);
		lap.setAvgPower(avgPower);
		lap.setAvgHr(avgHr);
		return lap;
	}

	// --- compressPass -------------------------------------------------------------------

	@Test
	void detectsAFlatRepeatedWorkRestPattern() {
		List<WorkoutInferenceService.Node> leaves = List.of(leaf(300, TargetType.POWER, 118.0),
				leaf(180, TargetType.POWER, 55.0), leaf(300, TargetType.POWER, 118.0),
				leaf(180, TargetType.POWER, 55.0), leaf(300, TargetType.POWER, 118.0),
				leaf(180, TargetType.POWER, 55.0));

		List<WorkoutInferenceService.Node> result = WorkoutInferenceService.compressPass(leaves);

		assertThat(result).hasSize(1);
		var group = (WorkoutInferenceService.Node.Group) result.get(0);
		assertThat(group.repeat()).isEqualTo(3);
		assertThat(group.children()).containsExactly(leaf(300, TargetType.POWER, 118.0), leaf(180, TargetType.POWER, 55.0));
	}

	@Test
	void toleratesMinorJitterBetweenReps() {
		List<WorkoutInferenceService.Node> leaves = List.of(leaf(300, TargetType.POWER, 118.0),
				leaf(180, TargetType.POWER, 55.0), leaf(305, TargetType.POWER, 120.0),
				leaf(178, TargetType.POWER, 53.0), leaf(298, TargetType.POWER, 116.0),
				leaf(182, TargetType.POWER, 56.0));

		List<WorkoutInferenceService.Node> result = WorkoutInferenceService.compressPass(leaves);

		assertThat(result).hasSize(1);
		assertThat(((WorkoutInferenceService.Node.Group) result.get(0)).repeat()).isEqualTo(3);
	}

	@Test
	void noRepetitionStaysFlat() {
		List<WorkoutInferenceService.Node> leaves = List.of(leaf(600, TargetType.POWER, 55.0),
				leaf(1200, TargetType.POWER, 88.0), leaf(300, TargetType.POWER, 40.0));

		assertThat(WorkoutInferenceService.compressPass(leaves)).isEqualTo(leaves);
	}

	@Test
	void recompressesTwoEquivalentGroupsIntoAnOuterGroup() {
		var innerA = new WorkoutInferenceService.Node.Group(4,
				List.of(leaf(240, TargetType.POWER, 100.0), leaf(60, TargetType.POWER, 50.0)));
		var innerB = new WorkoutInferenceService.Node.Group(4,
				List.of(leaf(241, TargetType.POWER, 101.0), leaf(59, TargetType.POWER, 51.0)));

		List<WorkoutInferenceService.Node> result = WorkoutInferenceService.compressPass(List.of(innerA, innerB));

		assertThat(result).hasSize(1);
		var outer = (WorkoutInferenceService.Node.Group) result.get(0);
		assertThat(outer.repeat()).isEqualTo(2);
		assertThat(outer.children()).containsExactly(innerA);
	}

	@Test
	void requiresAtLeastTwoRepetitions() {
		List<WorkoutInferenceService.Node> leaves = List.of(leaf(300, TargetType.POWER, 118.0),
				leaf(180, TargetType.POWER, 55.0), leaf(400, TargetType.POWER, 90.0));

		assertThat(WorkoutInferenceService.compressPass(leaves)).isEqualTo(leaves);
	}

	// --- infer ---------------------------------------------------------------------------

	@Test
	void infersWarmupRepeatGroupAndCooldown() {
		User athlete = new User();
		athlete.setEmail("infer@example.cc");
		athlete.setPassword("x");
		athlete.setName("Athlete");
		athlete.setFtp(265);
		Activity activity = activity(athlete, Sport.BIKE);
		List<Lap> laps = List.of(lap(0, 600, 145, null), lap(1, 300, 313, null), lap(2, 180, 146, null),
				lap(3, 300, 315, null), lap(4, 180, 143, null), lap(5, 300, 312, null), lap(6, 180, 145, null),
				lap(7, 300, 106, null));

		InferredWorkoutResponse result = service.infer(activity, laps, true);

		assertThat(result.sport()).isEqualTo(Sport.BIKE);
		List<StepKind> kinds = result.steps().stream().map(WorkoutStepDto::kind).toList();
		assertThat(kinds).containsExactly(StepKind.WARMUP, StepKind.REPEAT, StepKind.COOL);
		WorkoutStepDto group = result.steps().get(1);
		assertThat(group.repeat()).isEqualTo(3);
		assertThat(group.children().stream().map(WorkoutStepDto::kind).toList()).containsExactly(StepKind.BLOCK,
				StepKind.REC);
		assertThat(group.children().get(0).targetLow()).isEqualTo(118.0);
	}

	@Test
	void autoDetectRepeatsFalseStaysFlat() {
		User athlete = new User();
		athlete.setEmail("infer2@example.cc");
		athlete.setPassword("x");
		athlete.setName("Athlete");
		athlete.setFtp(265);
		Activity activity = activity(athlete, Sport.BIKE);
		List<Lap> laps = List.of(lap(0, 300, 313, null), lap(1, 300, 313, null), lap(2, 300, 313, null),
				lap(3, 300, 313, null));

		InferredWorkoutResponse result = service.infer(activity, laps, false);

		assertThat(result.steps()).hasSize(4);
		assertThat(result.steps()).noneMatch(s -> s.kind() == StepKind.REPEAT);
	}

	@Test
	void outputIsAValidStepTreeForComputeDurationAndTss() {
		User athlete = new User();
		athlete.setEmail("infer3@example.cc");
		athlete.setPassword("x");
		athlete.setName("Athlete");
		athlete.setFtp(265);
		Activity activity = activity(athlete, Sport.BIKE);
		List<Lap> laps = List.of(lap(0, 600, 145, null), lap(1, 300, 313, null), lap(2, 180, 146, null),
				lap(3, 300, 315, null), lap(4, 180, 143, null));

		InferredWorkoutResponse result = service.infer(activity, laps, true);

		WorkoutCalculations.Result totals = WorkoutCalculations.computeDurationAndTss(result.steps());
		assertThat(totals.durationSeconds()).isEqualTo(laps.stream().mapToInt(Lap::getDuration).sum());
	}

	@Test
	void fallsBackToHeartRateWhenNoPower() {
		User athlete = new User();
		athlete.setEmail("infer4@example.cc");
		athlete.setPassword("x");
		athlete.setName("Athlete");
		athlete.setLthr(165);
		Activity activity = activity(athlete, Sport.RUN);
		List<Lap> laps = List.of(lap(0, 600, null, 132));

		InferredWorkoutResponse result = service.infer(activity, laps, true);

		assertThat(result.steps().get(0).targetType()).isEqualTo(TargetType.HR);
		assertThat(result.steps().get(0).targetLow()).isEqualTo(80.0);
	}

	@Test
	void openTargetWhenNoPowerOrHrData() {
		User athlete = new User();
		athlete.setEmail("infer5@example.cc");
		athlete.setPassword("x");
		athlete.setName("Athlete");
		Activity activity = activity(athlete, Sport.BIKE);
		List<Lap> laps = List.of(lap(0, 600, null, null));

		InferredWorkoutResponse result = service.infer(activity, laps, true);

		assertThat(result.steps().get(0).targetType()).isEqualTo(TargetType.OPEN);
	}
}
