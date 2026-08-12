package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
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

/** End-to-end: a real FIT file, through the actual upload pipeline (ParseFileTasklet ->
 * ComputeDerivedStatsTasklet), ends up with the right threshold snapshot on the resulting
 * Activity - not just that the setter calls compile. */
class ThresholdSnapshotIngestionTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;

	@Test
	void runIngestionSnapshotsCriticalRunPowerAndThresholdPaceButNotFtp() throws Exception {
		User athlete = new User();
		athlete.setEmail("threshold-snapshot-ingest@example.cc");
		athlete.setName("Threshold Snapshot Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		athlete.setFtp(250);
		athlete.setCriticalRunPower(280);
		athlete.setThresholdPace("4:15");
		athlete = userRepository.save(athlete);

		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader()
				.getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		MockMultipartFile file = new MockMultipartFile(
				"file", "running_outdoor_marathon.fit", "application/octet-stream", fitBytes);
		Upload upload = uploadService.createSingleUpload(athlete, file, null, null, null, null);

		Activity activity = awaitReadyActivity(upload.getId());
		assertThat(activity.getSport()).isEqualTo(Sport.RUN);
		assertThat(activity.getCriticalRunPowerSnapshot()).isEqualTo(280);
		assertThat(activity.getThresholdPaceSnapshot()).isEqualTo("4:15");
		assertThat(activity.getFtpSnapshot()).isNull();
	}

	@Test
	void runningMarathonSuggestsAFasterThresholdPaceFromASlowStartingPoint() throws Exception {
		// An outdoor marathon has well over an hour of real GPS pace data - any real pace beats
		// an intentionally slow (10:00/km) starting threshold_pace.
		User athlete = new User();
		athlete.setEmail("threshold-detection-slow-pace@example.cc");
		athlete.setName("Slow Pace Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		athlete.setThresholdPace("10:00");
		athlete = userRepository.save(athlete);

		Upload upload =
				uploadService.createSingleUpload(athlete, fitFixture("running_outdoor_marathon.fit"), null, null, null, null);
		Activity activity = awaitReadyActivity(upload.getId());

		assertThat(activity.getSuggestedThresholdPace()).isNotNull();
		assertThat(activity.getSuggestedThresholdPace()).isNotEmpty();
		assertThat(activity.isThresholdChecked()).isTrue();
	}

	@Test
	void runningMarathonSuggestsNothingWhenThresholdPaceIsAlreadyFastEnough() throws Exception {
		User athlete = new User();
		athlete.setEmail("threshold-detection-fast-pace@example.cc");
		athlete.setName("Fast Pace Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		athlete.setThresholdPace("2:00"); // faster than any real marathon pace
		athlete = userRepository.save(athlete);

		Upload upload =
				uploadService.createSingleUpload(athlete, fitFixture("running_outdoor_marathon.fit"), null, null, null, null);
		Activity activity = awaitReadyActivity(upload.getId());

		assertThat(activity.getSuggestedThresholdPace()).isEmpty();
		// Still marked checked - "no suggestion" and "never checked" must stay distinguishable.
		assertThat(activity.isThresholdChecked()).isTrue();
	}

	private MockMultipartFile fitFixture(String name) throws Exception {
		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/" + name)) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		return new MockMultipartFile("file", name, "application/octet-stream", fitBytes);
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
