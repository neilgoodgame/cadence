package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivitySourceOrigin;
import com.cadence.api.activities.ActivitySourceRepository;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** End-to-end: running_outdoor_marathon.fit is a real Stryd-only device (no native power meter at
 * all - see FitFileParserTest) - under the "native" preference, its power must be completely
 * ignored end to end, not fall back to the only data present. See
 * ParseFileTasklet.selectRunningPowerSource. */
class RunningPowerSourceIngestionTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private BestEffortRepository bestEffortRepository;
	@Autowired
	private ZoneService zoneService;
	@Autowired
	private ActivitySourceRepository sourceRepository;

	private User newAthlete(String email, RunningPowerSource runningPowerSource) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setRunningPowerSource(runningPowerSource);
		return userRepository.save(user);
	}

	private Activity uploadStrydOnlyFixture(User athlete) throws Exception {
		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		MockMultipartFile file = new MockMultipartFile(
				"file", "running_outdoor_marathon.fit", "application/octet-stream", fitBytes);
		Upload upload = uploadService.createSingleUpload(athlete, file, null, null, null, null, null);
		return awaitReadyActivity(upload.getId());
	}

	@Test
	void defaultStrydPreferenceIngestsThisStrydOnlyFilesPowerNormally() throws Exception {
		User athlete = newAthlete("power-source-stryd-default@example.cc", RunningPowerSource.STRYD);

		Activity activity = uploadStrydOnlyFixture(athlete);

		assertThat(activity.getPowerSource()).isEqualTo(RunningPowerSource.STRYD);
		assertThat(recordRepository.findByActivityIdOrderByT(activity.getId()))
				.anySatisfy(r -> assertThat(r.getPower()).isNotNull());
		assertThat(activity.getNormPower()).isNotNull();
		assertThat(bestEffortRepository.findByAthleteIdAndKindAndActivityPrimaryActivityIsNullOrderByWindowAscValueDesc(
				athlete.getId(), BestEffortKind.RUNNING_POWER)).isNotEmpty();
	}

	@Test
	void nativePreferenceIgnoresThisStrydOnlyFilesPowerEntirely() throws Exception {
		User athlete = newAthlete("power-source-native@example.cc", RunningPowerSource.NATIVE);

		Activity activity = uploadStrydOnlyFixture(athlete);

		assertThat(activity.getPowerSource()).isEqualTo(RunningPowerSource.NATIVE);
		assertThat(recordRepository.findByActivityIdOrderByT(activity.getId()))
				.allSatisfy(r -> assertThat(r.getPower()).isNull());
		assertThat(activity.getNormPower()).isNull();
		assertThat(bestEffortRepository.findByAthleteIdAndKindAndActivityPrimaryActivityIsNullOrderByWindowAscValueDesc(
				athlete.getId(), BestEffortKind.RUNNING_POWER)).isEmpty();
		assertThat(zoneService.referenceFor(athlete, ZoneType.RUN_POWER, activity)).isNull();
	}

	/** Issue #503 (phase 2): running_outdoor_marathon.fit is Stryd-only - there's genuinely no
	 * native candidate in this file. Under the "stryd" preference (stryd primary, native is "the
	 * other" candidate), createParseTimeRunningPowerSource must not create anything - the file
	 * never had the other candidate at all (see its own Javadoc, "no-op if the other candidate
	 * has no data at all"). Under "native" preference, though, stryd itself becomes "the other"
	 * candidate - and the file DOES have that - so a source/stream must be created, even though
	 * native (now primary) has no data of its own (see nativePreferenceIgnoresThisStrydOnlyFiles
	 * PowerEntirely above for that half). */
	@Test
	void parseTimeDualKeepCreatesNothingWhenTheOtherCandidateIsEntirelyAbsentButSomethingWhenItIsnt() throws Exception {
		User strydPreferenceAthlete = newAthlete("power-source-dual-keep-stryd@example.cc", RunningPowerSource.STRYD);
		User nativePreferenceAthlete = newAthlete("power-source-dual-keep-native@example.cc", RunningPowerSource.NATIVE);

		Activity strydPreferenceActivity = uploadStrydOnlyFixture(strydPreferenceAthlete);
		Activity nativePreferenceActivity = uploadStrydOnlyFixture(nativePreferenceAthlete);

		// stryd preference: native is "the other" candidate, and this file never had it.
		assertThat(sourceRepository.findByActivityIdOrderByCreatedAtAsc(strydPreferenceActivity.getId())).isEmpty();

		// native preference: stryd is "the other" candidate, and this file DOES have it.
		var sources = sourceRepository.findByActivityIdOrderByCreatedAtAsc(nativePreferenceActivity.getId());
		assertThat(sources).hasSize(1);
		assertThat(sources.get(0).getSourceKind()).isEqualTo("stryd");
		assertThat(sources.get(0).getOrigin()).isEqualTo(ActivitySourceOrigin.FILE);
	}

	private Activity awaitReadyActivity(String uploadId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			Upload current = uploadRepository.findById(uploadId).orElseThrow();
			if (current.getStatus() == UploadStatus.READY) {
				return activityRepository.findById(current.getActivity().getId()).orElseThrow();
			}
			if (current.getStatus() != UploadStatus.QUEUED && current.getStatus() != UploadStatus.PROCESSING) {
				throw new AssertionError("Upload finished in unexpected status: " + current.getStatus());
			}
			Thread.sleep(200);
		}
		throw new AssertionError("Timed out waiting for upload to finish processing");
	}
}
