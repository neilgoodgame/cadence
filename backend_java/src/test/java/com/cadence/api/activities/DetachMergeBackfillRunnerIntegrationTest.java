package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/** Issue #542's backfill for attached sources that predate PR #575 - see the runner's own
 * Javadoc. Every assertion is scoped by {@code --activity=} (or checks a specific source id)
 * rather than a global orphan count: {@code ActivitySourceServiceIntegrationTest}'s own fixtures
 * deliberately leave some attaches undetached in this same shared integration database (per
 * backend_java/CLAUDE.md's fixture-collision gotcha), so those are real, permanent "orphans" too
 * as far as this runner is concerned - a global count would be flaky depending on test order. */
class DetachMergeBackfillRunnerIntegrationTest extends IntegrationTest {

	@Autowired
	private DetachMergeBackfillRunner runner;
	@Autowired
	private ActivitySourceService sourceService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivitySourceRepository sourceRepository;
	@Autowired
	private MergeRepository mergeRepository;
	@Autowired
	private UserRepository userRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String device, Instant startDate) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike on " + startDate);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		return activityRepository.save(activity);
	}

	private void addRecords(Activity activity, int count, int power) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	/** attachStream alone (no ActivityMergeService wrapper) never creates a Merge row - exactly
	 * the pre-PR-575 shape this backfill targets. */
	private ActivitySourceService.AttachResult orphanedAttach(User athlete, Instant start) {
		Activity target = newActivity(athlete, "Edge 530", start);
		Activity source = newActivity(athlete, "Zwift", start);
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);
		return sourceService.attachStream(target.getId(), source.getId(), "power", 0, false, null);
	}

	@Test
	void withNoBackfillFlagDoesAbsolutelyNothing() {
		User athlete = newUser("detach-backfill-noop@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		ActivitySourceService.AttachResult attach = orphanedAttach(athlete, start);
		String targetId = sourceRepository.findById(attach.sourceId()).orElseThrow().getActivity().getId();

		runner.run(new DefaultApplicationArguments());

		assertThat(mergeRepository.findByTargetActivityId(targetId)).isEmpty();
		assertThat(sourceRepository.findById(attach.sourceId())).isPresent();
	}

	@Test
	void reportModeIsTheDefaultAndCreatesNothing() {
		User athlete = newUser("detach-backfill-report@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		ActivitySourceService.AttachResult attach = orphanedAttach(athlete, start);
		String targetId = sourceRepository.findById(attach.sourceId()).orElseThrow().getActivity().getId();

		runner.run(new DefaultApplicationArguments("--backfill-detach-merges"));

		assertThat(mergeRepository.findByTargetActivityId(targetId)).isEmpty();
	}

	@Test
	void applyModeCreatesACorrectlyShapedMergeRowScopedToOneActivity() {
		User athlete = newUser("detach-backfill-apply@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		ActivitySourceService.AttachResult attach = orphanedAttach(athlete, start);
		String targetId = sourceRepository.findById(attach.sourceId()).orElseThrow().getActivity().getId();

		runner.run(new DefaultApplicationArguments("--backfill-detach-merges", "--mode=apply",
				"--activity=" + targetId));

		List<Merge> merges = mergeRepository.findByTargetActivityId(targetId);
		assertThat(merges).hasSize(1);
		Merge merge = merges.get(0);
		assertThat(merge.getSourceActivity()).isNull();
		assertThat(merge.getFields()).containsExactly("power");
		assertThat(merge.getNullPolicy()).isNull();
		assertThat(merge.getCopyMode()).isEqualTo(MergeCopyMode.ATTACH);
		assertThat(merge.getAttachedSource().getId()).isEqualTo(attach.sourceId());
		assertThat(merge.getDemotedSource()).isNull();
		assertThat(merge.getOffsetSeconds()).isNull();
		assertThat(merge.getAlignmentScore()).isNull();
		assertThat(merge.getFieldCounts()).isEmpty();
		assertThat(merge.getPreviousActivityValues()).isEmpty();
		assertThat(merge.isSourceHidden()).isFalse();
		assertThat(merge.getUndoneAt()).isNull();
	}

	@Test
	void applyModeBackdatesCreatedAtToTheSourcesOwn() {
		User athlete = newUser("detach-backfill-createdat@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		ActivitySourceService.AttachResult attach = orphanedAttach(athlete, start);
		ActivitySource source = sourceRepository.findById(attach.sourceId()).orElseThrow();
		String targetId = source.getActivity().getId();

		runner.run(new DefaultApplicationArguments("--backfill-detach-merges", "--mode=apply",
				"--activity=" + targetId));

		Merge merge = mergeRepository.findByTargetActivityId(targetId).get(0);
		assertThat(merge.getCreatedAt()).isEqualTo(source.getCreatedAt());
	}

	@Test
	void applyModeIsIdempotent() {
		User athlete = newUser("detach-backfill-idempotent@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		ActivitySourceService.AttachResult attach = orphanedAttach(athlete, start);
		String targetId = sourceRepository.findById(attach.sourceId()).orElseThrow().getActivity().getId();
		String args0 = "--backfill-detach-merges";
		String args1 = "--mode=apply";
		String args2 = "--activity=" + targetId;

		runner.run(new DefaultApplicationArguments(args0, args1, args2));
		runner.run(new DefaultApplicationArguments(args0, args1, args2));

		assertThat(mergeRepository.findByTargetActivityId(targetId)).hasSize(1);
	}

	@Test
	void aTrackedAttachMergeIsNotReportedAsAnOrphan() {
		User athlete = newUser("detach-backfill-tracked@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = newActivity(athlete, "Edge 530", start);
		Activity source = newActivity(athlete, "Zwift", start);
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);
		Merge trackedMerge = new Merge();
		trackedMerge.setTargetActivity(target);
		trackedMerge.setSourceActivity(source);
		trackedMerge.setFields(List.of("power"));
		trackedMerge.setCopyMode(MergeCopyMode.ATTACH);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(),
				"power", 0, false, null);
		trackedMerge.setAttachedSource(sourceRepository.findById(attach.sourceId()).orElseThrow());
		trackedMerge.setFieldCounts(java.util.Map.of());
		trackedMerge.setPreviousActivityValues(java.util.Map.of());
		mergeRepository.save(trackedMerge);

		runner.run(new DefaultApplicationArguments("--backfill-detach-merges", "--mode=apply",
				"--activity=" + target.getId()));

		assertThat(mergeRepository.findByTargetActivityId(target.getId())).hasSize(1);
	}
}
