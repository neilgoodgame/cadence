package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.DuplicateResolutionBatchService.ExplicitPair;
import com.cadence.api.activities.DuplicateResolutionBatchService.OverlapQueryFilters;
import com.cadence.api.activities.DuplicateResolutionBatchService.ResolveBatchResult;
import com.cadence.api.activities.DuplicateResolutionBatchService.UndoBatchResult;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #498's resolveDuplicatesBatch/undoBatch - the primary-selection/mark/flag logic itself
 * is covered directly (and far more cheaply) by ActivityOverlapDetectionServiceIntegrationTest;
 * this exercises the batch's own pairs-vs-query selection, dry-run preview, apply/persist/
 * recompute wiring, and undo. Mirrors the Python backend's
 * activities/tests/test_duplicate_batches.py. */
class DuplicateResolutionBatchServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private DuplicateResolutionBatchService batchService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BikeRepository bikeRepository;

	@Autowired
	private DuplicateResolutionBatchRepository batchRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String device, Instant startDate, int elapsedSeconds) {
		return newActivity(athlete, device, startDate, elapsedSeconds, null, 0);
	}

	private Activity newActivity(User athlete, String device, Instant startDate, int elapsedSeconds, Bike bike, double distanceKm) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike on " + startDate);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		activity.setElapsedSeconds(elapsedSeconds);
		activity.setMovingTime(elapsedSeconds);
		activity.setBike(bike);
		activity.setDistanceKm(distanceKm);
		return activityRepository.save(activity);
	}

	private Bike newBike(User athlete, int distanceKm) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Kickr bike");
		bike.setDistanceKm(distanceKm);
		return bikeRepository.save(bike);
	}

	@Test
	void dryRunWithExplicitPairsPreviewsWithoutMutatingAnything() {
		User athlete = newUser("dup-batch-dry-run@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, true, athlete);

		assertThat(result.dryRun()).isTrue();
		assertThat(result.batchId()).isNull();
		assertThat(result.results()).hasSize(1);
		assertThat(result.results().get(0).action()).isEqualTo(OverlapAction.MARKED);
		assertThat(result.results().get(0).primaryActivityId()).isEqualTo(zwift.getId());
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
		assertThat(batchRepository.existsByAthleteId(athlete.getId())).isFalse();
	}

	@Test
	void applyWithExplicitPairsMarksAndPersistsAnUndoableBatch() {
		User athlete = newUser("dup-batch-apply@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);

		assertThat(result.dryRun()).isFalse();
		assertThat(result.batchId()).isNotNull();
		assertThat(result.results().get(0).action()).isEqualTo(OverlapAction.MARKED);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(zwift.getId());
		DuplicateResolutionBatch batch = batchRepository.findById(result.batchId()).orElseThrow();
		assertThat(batch.getAthlete().getId()).isEqualTo(athlete.getId());
		assertThat(batch.getAction()).isEqualTo(OverlapHandling.MARK);
	}

	@Test
	void applyRetractsTheMarkedDuplicatesGearMileage() {
		User athlete = newUser("dup-batch-mileage@example.cc");
		Bike bike = newBike(athlete, 142);
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600, bike, 42.0);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600, bike, 42.0);

		batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);
	}

	@Test
	void applyUsingTheOverlapQuerysOwnFiltersInsteadOfPairs() {
		User athlete = newUser("dup-batch-query@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		OverlapQueryFilters filters = new OverlapQueryFilters(null, Sport.BIKE, false,
				null, null, null, null, null, null, null, null, null, null);

		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK, null, filters, false, athlete);

		assertThat(result.results()).hasSize(1);
		var pairResult = result.results().get(0);
		assertThat(pairResult.action()).isEqualTo(OverlapAction.MARKED);
		assertThat(pairResult.primaryActivityId()).isEqualTo(zwift.getId());
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(zwift.getId());
	}

	@Test
	void mergeActionFallsBackToFlagUntilIssue501Ships() {
		User athlete = newUser("dup-batch-merge@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MERGE,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);

		assertThat(result.results().get(0).action()).isEqualTo(OverlapAction.FLAGGED);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void rejectsAnUnknownAction() {
		User athlete = newUser("dup-batch-bad-action@example.cc");

		assertThatThrownBy(() -> batchService.resolveDuplicatesBatch(athlete, OverlapHandling.OFF,
				List.of(new ExplicitPair("a", "b")), null, true, athlete))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsPassingBothPairsAndQueryFilters() {
		User athlete = newUser("dup-batch-both@example.cc");
		OverlapQueryFilters filters = new OverlapQueryFilters(null, Sport.BIKE, false,
				null, null, null, null, null, null, null, null, null, null);

		assertThatThrownBy(() -> batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair("a", "b")), filters, true, athlete))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsAPairReferencingAnotherAthletesActivity() {
		User athlete = newUser("dup-batch-outsider-athlete@example.cc");
		User otherAthlete = newUser("dup-batch-outsider-other@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity mine = newActivity(athlete, "Zwift", start, 3600);
		Activity theirs = newActivity(otherAthlete, "Garmin", start.plusSeconds(5), 3600);

		assertThatThrownBy(() -> batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(mine.getId(), theirs.getId())), null, false, athlete))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void undoBatchRevertsAMarkedPairAndRestoresGearMileage() {
		User athlete = newUser("dup-batch-undo@example.cc");
		Bike bike = newBike(athlete, 142);
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600, bike, 42.0);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600, bike, 42.0);
		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);
		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);

		UndoBatchResult undoResult = batchService.undoBatch(athlete, result.batchId());

		assertThat(undoResult.revertedCount()).isEqualTo(1);
		assertThat(undoResult.skippedCount()).isZero();
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
	}

	@Test
	void undoBatchSkipsAnEntryThatsBeenReResolvedSince() {
		User athlete = newUser("dup-batch-undo-skip@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);
		Activity otherPrimary = newActivity(athlete, "Wahoo", Instant.parse("2026-01-01T00:00:00Z"), 3600);
		Activity reloadedGarmin = activityRepository.findById(garmin.getId()).orElseThrow();
		reloadedGarmin.setPrimaryActivity(otherPrimary);
		activityRepository.save(reloadedGarmin);

		UndoBatchResult undoResult = batchService.undoBatch(athlete, result.batchId());

		assertThat(undoResult.revertedCount()).isZero();
		assertThat(undoResult.skippedCount()).isEqualTo(1);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(otherPrimary.getId());
	}

	@Test
	void undoBatchRejectsUndoingTheSameBatchTwice() {
		User athlete = newUser("dup-batch-undo-twice@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);
		batchService.undoBatch(athlete, result.batchId());

		assertThatThrownBy(() -> batchService.undoBatch(athlete, result.batchId()))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void undoBatchRejectsAnotherAthletesBatch() {
		User athlete = newUser("dup-batch-undo-outsider-athlete@example.cc");
		User otherAthlete = newUser("dup-batch-undo-outsider-other@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MARK,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);

		assertThatThrownBy(() -> batchService.undoBatch(otherAthlete, result.batchId()))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void undoBatchLeavesAFlaggedOnlyEntryAlone() {
		User athlete = newUser("dup-batch-undo-flagged@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		ResolveBatchResult result = batchService.resolveDuplicatesBatch(athlete, OverlapHandling.MERGE,
				List.of(new ExplicitPair(garmin.getId(), zwift.getId())), null, false, athlete);

		UndoBatchResult undoResult = batchService.undoBatch(athlete, result.batchId());

		assertThat(undoResult.revertedCount()).isZero();
		assertThat(undoResult.skippedCount()).isZero();
	}
}
