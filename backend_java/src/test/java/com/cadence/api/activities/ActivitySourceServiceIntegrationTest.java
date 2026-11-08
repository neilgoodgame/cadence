package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.BikePowerSource;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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

	private Activity newRunActivity(User athlete, RunningPowerSource powerSource) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setName("Run");
		activity.setStartDate(START);
		activity.setPowerSource(powerSource);
		return activityRepository.save(activity);
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

	@Test
	void parseTimeDualKeepCreatesANonPrimarySourceWhenBothCandidatesArePresent() {
		User athlete = newAthlete("source-parse-time-both-present@example.cc");
		Activity activity = newRunActivity(athlete, RunningPowerSource.STRYD);
		addRecords(activity, 5, 200); // the primary (Stryd) stream, already written to Record

		List<Integer> tSeries = List.of(0, 1, 2, 3, 4);
		List<Integer> otherValues = Arrays.asList(250, 251, null, 253, 254); // native, the other candidate

		sourceService.createParseTimeRunningPowerSource(activity, tSeries, otherValues, RunningPowerSource.NATIVE);

		List<ActivitySource> sources = sourceRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId());
		assertThat(sources).hasSize(1);
		ActivitySource source = sources.get(0);
		assertThat(source.getOrigin()).isEqualTo(ActivitySourceOrigin.FILE);
		assertThat(source.getSensorKind()).isEqualTo("power");
		assertThat(source.getRunningPowerKind()).isEqualTo("native");

		ActivityStream stream = streamRepository
				.findByActivityIdAndFieldAndSourceId(activity.getId(), "power", source.getId()).orElseThrow();
		assertThat(stream.isPrimaryOf()).isFalse();
		assertThat(stream.getStreamValues()).containsExactly((short) 250, (short) 251, null, (short) 253, (short) 254);

		// The primary (Stryd) Record values are completely untouched.
		assertThat(recordRepository.findByActivityIdOrderByT(activity.getId()))
				.allSatisfy(r -> assertThat(r.getPower()).isEqualTo(200));
	}

	@Test
	void parseTimeDualKeepCreatesNothingWhenTheOtherCandidateIsEntirelyAbsent() {
		User athlete = newAthlete("source-parse-time-other-absent@example.cc");
		Activity activity = newRunActivity(athlete, RunningPowerSource.STRYD);
		addRecords(activity, 5, 200);

		List<Integer> tSeries = List.of(0, 1, 2, 3, 4);
		List<Integer> otherValues = Arrays.asList(null, null, null, null, null); // native never present

		sourceService.createParseTimeRunningPowerSource(activity, tSeries, otherValues, RunningPowerSource.NATIVE);

		assertThat(sourceRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId())).isEmpty();
	}

	@Test
	void setPrimarySourceOnARunStampsPowerSourceFromTheStoredRunningPowerKind() {
		User athlete = newAthlete("source-run-set-primary@example.cc");
		Activity activity = newRunActivity(athlete, RunningPowerSource.STRYD);
		addRecords(activity, 5, 200); // Stryd primary
		sourceService.createParseTimeRunningPowerSource(activity, List.of(0, 1, 2, 3, 4),
				List.of(250, 251, 252, 253, 254), RunningPowerSource.NATIVE);
		ActivitySource nativeSource = sourceRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId()).get(0);

		sourceService.setPrimarySource(activity.getId(), "power", nativeSource.getId());

		List<Record> rowsAfterSwap = recordRepository.findByActivityIdOrderByT(activity.getId());
		assertThat(rowsAfterSwap).extracting(Record::getPower).containsExactly(250, 251, 252, 253, 254);
		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getPowerSource()).isEqualTo(RunningPowerSource.NATIVE);

		// The original (Stryd, 200) primary was itself snapshotted as a new source, tagged with
		// its own running-power kind - not left blank/device-classified the way bike's is.
		ActivitySource strydSnapshot = sourceRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId()).stream()
				.filter(s -> !s.getId().equals(nativeSource.getId()))
				.findFirst().orElseThrow();
		assertThat(strydSnapshot.getRunningPowerKind()).isEqualTo("stryd");

		// And it's reversible, the same as bike's.
		sourceService.setPrimarySource(activity.getId(), "power", strydSnapshot.getId());
		Activity reloadedAfterSwapBack = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloadedAfterSwapBack.getPowerSource()).isEqualTo(RunningPowerSource.STRYD);
		assertThat(recordRepository.findByActivityIdOrderByT(activity.getId()))
				.allSatisfy(r -> assertThat(r.getPower()).isEqualTo(200));
	}

	@Test
	void listSourcesIsPrimaryForARunUsesPowerSourceNotDeviceClassification() {
		User athlete = newAthlete("source-run-list-is-primary@example.cc");
		Activity activity = newRunActivity(athlete, RunningPowerSource.STRYD);
		addRecords(activity, 5, 200);
		sourceService.createParseTimeRunningPowerSource(activity, List.of(0, 1, 2, 3, 4),
				List.of(250, 251, 252, 253, 254), RunningPowerSource.NATIVE);
		ActivitySource nativeSource = sourceRepository.findByActivityIdOrderByCreatedAtAsc(activity.getId()).get(0);

		List<ActivitySourceService.SourceSummary> summaries = sourceService.listSources(activity.getId());
		assertThat(summaries).hasSize(1);
		// The stored native source doesn't match the activity's current (Stryd) primary yet.
		assertThat(summaries.get(0).isPrimary()).isFalse();

		sourceService.setPrimarySource(activity.getId(), "power", nativeSource.getId());

		List<ActivitySourceService.SourceSummary> summariesAfterSwap = sourceService.listSources(activity.getId());
		assertThat(summariesAfterSwap).filteredOn(s -> s.id().equals(nativeSource.getId()))
				.singleElement().satisfies(s -> assertThat(s.isPrimary()).isTrue());
	}
}
