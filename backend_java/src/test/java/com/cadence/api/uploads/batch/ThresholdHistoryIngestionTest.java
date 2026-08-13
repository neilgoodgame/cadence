package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * End-to-end: a real FIT file, through the actual upload pipeline (ThresholdHistoryTasklet ->
 * ComputeDerivedStatsTasklet, in that order), ends up with a ThresholdHistory ledger entry - and
 * this same activity's own TSS/intensity is rated against the value that entry establishes, not
 * left to fall back to the athlete's stale starting profile (see ThresholdHistoryTasklet's
 * Javadoc: the effort that reveals a new threshold is itself re-rated against it).
 */
class ThresholdHistoryIngestionTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ZoneService zoneService;

	@Test
	void runIngestionEstablishesLedgerEntriesTheActivityIsItselfRatedAgainst() throws Exception {
		// An outdoor marathon has well over an hour of real GPS pace + Stryd power data - easily
		// qualifies as the current-window-best from a fresh (no prior history) athlete's starting
		// point of "no evidence yet."
		User athlete = new User();
		athlete.setEmail("threshold-history-ingest@example.cc");
		athlete.setName("Threshold History Athlete");
		athlete.setPassword("irrelevant-for-this-test");
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

		List<ThresholdHistory> entries = thresholdHistoryRepository.findBySourceActivityId(activity.getId());
		assertThat(entries).isNotEmpty();
		assertThat(entries).anySatisfy(entry -> assertThat(entry.getField()).isEqualTo(ThresholdField.THRESHOLD_PACE));

		// The activity's own zones resolve via the ledger entry it just established, not "unknown."
		assertThat(zoneService.referenceFor(activity.getAthlete(), ZoneType.PACE, activity)).isNotNull();

		// And the athlete's live profile was updated to match - refetch since the in-memory
		// `athlete` reference predates the ingest pipeline's own save.
		User reloaded = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(reloaded.getThresholdPace()).isNotEmpty();
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
