package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.ActivityMergeApplyService.Segment;
import com.cadence.api.activities.ActivityStreamFlagService.Kind;
import com.cadence.api.activities.ActivityStreamPatchService.StreamPatchResult;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #540 (phase 6): mirrors backend's activities/tests/test_stream_patch_service.py -
 * ActivityStreamPatchService.patchStream/unpatchStream, the write side of GET .../streams/flags. */
class StreamPatchServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private ActivityStreamPatchService streamPatchService;
	@Autowired
	private ActivitySourceService sourceService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private StreamPatchRepository streamPatchRepository;
	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name, String device) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setDevice(device);
		activity.setStartDate(START);
		return activityRepository.save(activity);
	}

	private void addRecordsWith(Activity activity, int count, java.util.function.BiConsumer<Record, Integer> customize) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			customize.accept(record, t);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	private void addConstant(Activity activity, int count, String field, int value) {
		addRecordsWith(activity, count, (record, t) -> ActivityMergeApplyService.setRecordFieldValue(record, field, value));
	}

	private record Fixture(Activity target, ActivitySourceService.AttachResult attach) {
	}

	private Fixture targetAndAttachedPowerSource(String athleteSuffix) {
		User athlete = newAthlete("java-patch-" + athleteSuffix + "@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		Activity source = newActivity(athlete, "Source", "Favero Assioma");
		addRecordsWith(target, 10, (record, t) -> record.setPower(t == 4 || t == 5 ? 0 : 200));
		addConstant(source, 10, "power", 220);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(), "power",
				0, false, null);
		return new Fixture(target, attach);
	}

	private Integer powerAt(Activity target, int t) {
		return recordRepository.findByActivityIdOrderByT(target.getId()).stream()
				.filter(r -> r.getT() == t).findFirst().orElseThrow().getPower();
	}

	@Test
	void patchAppliesExplicitSegmentsAndCreatesAnAuditRow() {
		Fixture fixture = targetAndAttachedPowerSource("explicit-commit");

		StreamPatchResult result = streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(4, 5)), null, null, false);

		assertThat(result.patchId()).isNotNull();
		assertThat(result.segments()).containsExactly(new Segment(4, 5));
		assertThat(result.detectedKind()).isNull();

		assertThat(powerAt(fixture.target(), 4)).isEqualTo(220);
		assertThat(powerAt(fixture.target(), 5)).isEqualTo(220);
		assertThat(powerAt(fixture.target(), 3)).isEqualTo(200); // untouched - outside the patched segment

		StreamPatch patch = streamPatchRepository.findById(result.patchId()).orElseThrow();
		assertThat(patch.getField()).isEqualTo("power");
		assertThat(patch.getFromSource().getId()).isEqualTo(fixture.attach().sourceId());
		assertThat(patch.getSegments()).containsExactly(List.of(4, 5));
		assertThat(patch.getPreviousValues()).containsOnly(Map.entry("4", 0), Map.entry("5", 0));
		assertThat(patch.getUndoneAt()).isNull();
	}

	@Test
	void patchPreviewWritesNothing() {
		Fixture fixture = targetAndAttachedPowerSource("preview");

		// See StreamPatchPreviewRollbackSignal - patchStream(preview=true) never returns
		// normally; it throws this to force its own transaction to roll back, carrying the
		// already-computed result. The REST controller/MCP tool catch it the same way.
		StreamPatchResult result;
		try {
			streamPatchService.patchStream(fixture.target().getId(), "power", fixture.attach().sourceId(),
					List.of(new Segment(4, 5)), null, null, true);
			throw new AssertionError("Expected a preview to throw StreamPatchPreviewRollbackSignal.");
		}
		catch (StreamPatchPreviewRollbackSignal signal) {
			result = signal.result();
		}

		assertThat(result.patchId()).isNull();
		assertThat(streamPatchRepository.findByActivityId(fixture.target().getId())).isEmpty();
		assertThat(powerAt(fixture.target(), 4)).isEqualTo(0);
		assertThat(powerAt(fixture.target(), 5)).isEqualTo(0);
	}

	@Test
	void patchWithFlaggedSegmentsUsesLiveDetectionAndRecordsDetectedKind() {
		Fixture fixture = targetAndAttachedPowerSource("flagged");

		StreamPatchResult result = streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), null, Kind.CONTACT_LOSS, fixture.attach().sourceId(), false);

		assertThat(result.segments()).containsExactly(new Segment(4, 5));
		assertThat(result.detectedKind()).isEqualTo("contact_loss");
		StreamPatch patch = streamPatchRepository.findById(result.patchId()).orElseThrow();
		assertThat(patch.getDetectedKind()).isEqualTo("contact_loss");
	}

	@Test
	void anOverlappingPatchIsRejectedThenAcceptedAfterUnpatch() {
		Fixture fixture = targetAndAttachedPowerSource("overlap");
		StreamPatchResult first = streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(4, 5)), null, null, false);

		assertThatThrownBy(() -> streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(5, 6)), null, null, false))
				.isInstanceOf(ConflictException.class);

		streamPatchService.unpatchStream(fixture.target().getId(), first.patchId());

		StreamPatchResult second = streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(5, 6)), null, null, false);
		assertThat(second.patchId()).isNotNull();
	}

	@Test
	void explicitSegmentsOverTheSanityCapAreRejected() {
		Fixture fixture = targetAndAttachedPowerSource("too-big");

		assertThatThrownBy(() -> streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(0, 4000)), null, null, false))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void unpatchRestoresPreviousValuesAndMarksUndone() {
		Fixture fixture = targetAndAttachedPowerSource("unpatch");
		StreamPatchResult applied = streamPatchService.patchStream(fixture.target().getId(), "power",
				fixture.attach().sourceId(), List.of(new Segment(4, 5)), null, null, false);

		StreamPatch patch = streamPatchService.unpatchStream(fixture.target().getId(), applied.patchId());

		assertThat(patch.getUndoneAt()).isNotNull();
		assertThat(powerAt(fixture.target(), 4)).isEqualTo(0);
		assertThat(powerAt(fixture.target(), 5)).isEqualTo(0);
	}

	@Test
	void unpatchIsAmbiguousWithoutPatchIdWhenMoreThanOneActive() {
		Fixture fixture = targetAndAttachedPowerSource("ambiguous");
		streamPatchService.patchStream(fixture.target().getId(), "power", fixture.attach().sourceId(),
				List.of(new Segment(4, 4)), null, null, false);
		streamPatchService.patchStream(fixture.target().getId(), "power", fixture.attach().sourceId(),
				List.of(new Segment(5, 5)), null, null, false);

		assertThatThrownBy(() -> streamPatchService.unpatchStream(fixture.target().getId(), null))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void unpatchWithAnUnknownPatchIdErrors() {
		Fixture fixture = targetAndAttachedPowerSource("unknown-id");

		assertThatThrownBy(() -> streamPatchService.unpatchStream(fixture.target().getId(), "pat_doesnotexist"))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	/** Code review finding: segments="flagged" skipped the sanity cap entirely on the theory
	 * that a detector's own run-length cap already bounds it - true for contact_loss's two
	 * detectors, but CadenceLockDetector's merged runs have no length cap of their own, so a
	 * long-running lock episode could otherwise patch an unbounded span. The cap is now applied
	 * to the resolved segments regardless of path; a real 3700s cadence-lock run (continuously
	 * correlated, so it merges into one run spanning the whole fixture) is long enough to exceed
	 * the 3600s cap without needing to shrink the constant for the test. */
	@Test
	void flaggedCadenceLockSegmentsAreStillCapped() {
		User athlete = newAthlete("java-patch-cadence-lock-cap@example.cc");
		Activity target = newActivity(athlete, "Target", "Epix");
		Activity source = newActivity(athlete, "Source", "Tickr");
		addRecordsWith(target, 3700, (record, t) -> {
			record.setHeartrate(t % 2 == 0 ? 152 : 148);
			record.setCadence(t % 2 == 0 ? 90 : 70);
		});
		addConstant(source, 3700, "heartrate", 150);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(),
				"heartrate", 0, false, "strap");

		assertThatThrownBy(() -> streamPatchService.patchStream(target.getId(), "heartrate", attach.sourceId(), null,
				Kind.CADENCE_LOCK, attach.sourceId(), false))
				.isInstanceOf(ValidationException.class);
	}

	/** Code review follow-up: closes the TOCTOU race between rejectOverlap's read and this
	 * method's own write - two concurrent patchStream calls on the same activity/field with
	 * overlapping segments must never both succeed. A CyclicBarrier starts both threads at
	 * (as close to) the same instant; repeated trials give real DB round-trip timing a good
	 * chance of actually overlapping at the critical section on at least one of them, and the
	 * invariant - never more than one of the two ever succeeds - must hold on every trial
	 * regardless. */
	@Test
	void concurrentOverlappingPatchesAreSerializedNotARace() throws Exception {
		for (int trial = 0; trial < 5; trial++) {
			Fixture fixture = targetAndAttachedPowerSource("concurrent-" + trial);
			CyclicBarrier barrier = new CyclicBarrier(2);
			List<StreamPatchResult> results = new ArrayList<>();
			List<Exception> errors = new ArrayList<>();

			Runnable attempt1 = concurrentAttempt(barrier, fixture, new Segment(4, 5), results, errors);
			Runnable attempt2 = concurrentAttempt(barrier, fixture, new Segment(5, 6), results, errors);
			Thread t1 = new Thread(attempt1);
			Thread t2 = new Thread(attempt2);
			t1.start();
			t2.start();
			t1.join();
			t2.join();

			assertThat(results).hasSize(1);
			assertThat(errors).hasSize(1);
			assertThat(errors.get(0)).isInstanceOf(ConflictException.class);
		}
	}

	private Runnable concurrentAttempt(CyclicBarrier barrier, Fixture fixture, Segment segment,
			List<StreamPatchResult> results, List<Exception> errors) {
		return () -> {
			try {
				barrier.await();
				StreamPatchResult result = streamPatchService.patchStream(fixture.target().getId(), "power",
						fixture.attach().sourceId(), List.of(segment), null, null, false);
				synchronized (results) {
					results.add(result);
				}
			}
			catch (ConflictException ex) {
				synchronized (errors) {
					errors.add(ex);
				}
			}
			catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		};
	}
}
