package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.BikePowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #503 (phase 1): mirrors backend's activities/tests/test_activity_source_service.py. */
class ActivitySourceServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private ActivitySourceService sourceService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivitySourceRepository sourceRepository;
	@Autowired
	private ActivityStreamRepository streamRepository;
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

	private void addRecords(Activity activity, int count, int power) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void attachCreatesANonPrimarySourceWithoutTouchingTheTargetsPrimary() {
		User athlete = newAthlete("source-attach-no-touch@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		Activity source = newActivity(athlete, "Source", "Zwift");
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);

		ActivitySourceService.AttachResult result = sourceService.attachPowerStream(target.getId(), source.getId(), 0, false);

		assertThat(result.sourceId()).isNotNull();
		assertThat(result.copied()).isEqualTo(10);
		assertThat(result.nulled()).isZero();
		// Default bikePowerSource is ANY - no preference, so attach never auto-swaps.
		assertThat(result.becamePrimary()).isFalse();

		// The target's own primary stream is untouched.
		List<Record> targetRows = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(targetRows).allSatisfy(r -> assertThat(r.getPower()).isEqualTo(200));

		ActivitySource savedSource = sourceRepository.findById(result.sourceId()).orElseThrow();
		assertThat(savedSource.getOrigin()).isEqualTo(ActivitySourceOrigin.ATTACHED);
		assertThat(savedSource.getSensorKind()).isEqualTo("power");
		assertThat(savedSource.getDevice()).isEqualTo("Zwift");

		ActivityStream stream = streamRepository
				.findByActivityIdAndFieldAndSourceId(target.getId(), "power", result.sourceId()).orElseThrow();
		assertThat(stream.isPrimaryOf()).isFalse();
		assertThat(stream.getStreamValues()).hasSize(10);
		assertThat(stream.getStreamValues()[0]).isEqualTo((short) 300);
	}

	@Test
	void attachAutoSwapsWhenTheAttachedSourceMatchesThePreference() {
		User athlete = newAthlete("source-attach-auto-swap@example.cc");
		athlete.setBikePowerSource(BikePowerSource.TRAINER);
		athlete = userRepository.save(athlete);
		Activity target = newActivity(athlete, "Target", "Edge 530"); // classifies PEDALS
		Activity source = newActivity(athlete, "Source", "Zwift"); // classifies TRAINER
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);

		ActivitySourceService.AttachResult result = sourceService.attachPowerStream(target.getId(), source.getId(), 0, false);

		assertThat(result.becamePrimary()).isTrue();
		List<Record> targetRows = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(targetRows).allSatisfy(r -> assertThat(r.getPower()).isEqualTo(300));
		Activity reloaded = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloaded.getBikePowerSource()).isEqualTo(BikePowerSource.TRAINER);
	}

	@Test
	void setPrimarySourceSwapsRecordValuesAndCanBeReversed() {
		User athlete = newAthlete("source-set-primary-reversible@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		Activity source = newActivity(athlete, "Source", "Zwift");
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);
		ActivitySourceService.AttachResult attach = sourceService.attachPowerStream(target.getId(), source.getId(), 0, false);
		assertThat(attach.becamePrimary()).isFalse(); // ANY preference by default - no auto-swap yet

		sourceService.setPrimarySource(target.getId(), "power", attach.sourceId());

		List<Record> afterSwap = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(afterSwap).allSatisfy(r -> assertThat(r.getPower()).isEqualTo(300));
		Activity reloadedAfterSwap = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloadedAfterSwap.getBikePowerSource()).isEqualTo(BikePowerSource.TRAINER);

		// The original ("Edge 530", 200) primary was itself snapshotted as a new source, not lost.
		List<ActivitySource> sources = sourceRepository.findByActivityIdOrderByCreatedAtAsc(target.getId());
		ActivitySource originalSnapshot = sources.stream()
				.filter(s -> s.getOrigin() == ActivitySourceOrigin.FILE)
				.findFirst().orElseThrow();
		assertThat(originalSnapshot.getDevice()).isEqualTo("Edge 530");

		sourceService.setPrimarySource(target.getId(), "power", originalSnapshot.getId());

		List<Record> afterSwapBack = recordRepository.findByActivityIdOrderByT(target.getId());
		assertThat(afterSwapBack).allSatisfy(r -> assertThat(r.getPower()).isEqualTo(200));
		Activity reloadedAfterSwapBack = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(reloadedAfterSwapBack.getBikePowerSource()).isEqualTo(BikePowerSource.PEDALS);
	}

	@Test
	void listSourcesReportsIsPrimaryAgainstTheActivitysCurrentPrimary() {
		User athlete = newAthlete("source-list-is-primary@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530"); // classifies PEDALS
		Activity source = newActivity(athlete, "Source", "Zwift"); // classifies TRAINER
		addRecords(target, 10, 200);
		addRecords(source, 10, 300);
		ActivitySourceService.AttachResult attach = sourceService.attachPowerStream(target.getId(), source.getId(), 0, false);

		List<ActivitySourceService.SourceSummary> summaries = sourceService.listSources(target.getId());

		assertThat(summaries).hasSize(1);
		// Target's current primary is still its own PEDALS reading - the attached TRAINER source
		// doesn't match it (yet).
		assertThat(summaries.get(0).id()).isEqualTo(attach.sourceId());
		assertThat(summaries.get(0).isPrimary()).isFalse();

		sourceService.setPrimarySource(target.getId(), "power", attach.sourceId());

		List<ActivitySourceService.SourceSummary> summariesAfterSwap = sourceService.listSources(target.getId());
		assertThat(summariesAfterSwap).filteredOn(s -> s.id().equals(attach.sourceId()))
				.singleElement().satisfies(s -> assertThat(s.isPrimary()).isTrue());
	}
}
