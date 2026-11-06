package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cadence.api.activities.ActivityService;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.OnDuplicate;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadIngestService;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.garmin.fit.DateTime;
import com.garmin.fit.File;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Issue #498: an ingest-time auto-mark must trigger the same post-mark recompute cascade
 * ({@code ActivityService.recomputeAfterDuplicateChange}) that {@code markDuplicate}/{@code
 * unmarkDuplicate}/{@code setPrimary} and {@code DuplicateResolutionBatchService} already run -
 * otherwise a duplicate-sourced best effort stays an eligible threshold candidate, and
 * TSS/intensity elsewhere stays stale, until something else happens to trigger a rebuild. Spies
 * on the real {@code ActivityService} bean rather than building a full threshold-ledger
 * scenario - the cascade's own correctness is covered directly by {@code
 * ThresholdHistoryServiceIntegrationTest} and friends; this only proves every auto-mark path reaches it,
 * exactly once per upload or batch.
 */
class RecomputeAfterIngestTimeMarkIntegrationTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@MockitoSpyBean
	private ActivityService activityService;

	@TempDir
	Path tempDir;

	private User newAthlete(String email, OverlapHandling overlapHandling) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		user.setOverlapHandling(overlapHandling);
		return userRepository.save(user);
	}

	/** Same start time and elapsed time within a pair - the simplest possible full overlap -
	 * offset far enough between pairs that they can never overlap each other. */
	private MockMultipartFile syntheticFit(String name, long startOffset, int power) throws Exception {
		Path path = tempDir.resolve(name);
		FileEncoder encoder = new FileEncoder(path.toFile(), Fit.ProtocolVersion.V2_0);
		FileIdMesg fileId = new FileIdMesg();
		fileId.setType(File.ACTIVITY);
		fileId.setManufacturer(Manufacturer.GARMIN);
		fileId.setTimeCreated(new DateTime(startOffset));
		encoder.write(fileId);

		SessionMesg session = new SessionMesg();
		session.setSport(Sport.CYCLING);
		session.setStartTime(new DateTime(startOffset));
		session.setTotalElapsedTime(3600.0f);
		encoder.write(session);

		RecordMesg record = new RecordMesg();
		record.setTimestamp(new DateTime(startOffset));
		record.setPower(power);
		encoder.write(record);
		encoder.close();
		return new MockMultipartFile("file", name, "application/octet-stream", Files.readAllBytes(path));
	}

	private Upload awaitTerminal(String uploadId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			Upload current = uploadRepository.findById(uploadId).orElseThrow();
			if (current.getStatus() != UploadStatus.QUEUED && current.getStatus() != UploadStatus.PROCESSING) {
				return current;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Timed out waiting for the upload to finish processing");
	}

	private List<Upload> awaitBatchTerminal(String batchId, int expectedCount) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			List<Upload> children = uploadService.getBatchChildren(batchId);
			if (children.size() == expectedCount && children.stream()
					.allMatch(u -> u.getStatus() != UploadStatus.QUEUED && u.getStatus() != UploadStatus.PROCESSING)) {
				return children;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Timed out waiting for the batch to finish processing");
	}

	@Test
	void singleUploadAutoMarkTriggersTheCascadeExactlyOnce() throws Exception {
		User athlete = newAthlete("e2e-single-overlap-recompute@example.cc", OverlapHandling.MARK);
		Upload first = uploadService.createSingleUpload(
				athlete, syntheticFit("a.fit", 0L, 200), null, null, null, null, null);
		awaitTerminal(first.getId());
		verify(activityService, never()).recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));

		Upload second = uploadService.createSingleUpload(
				athlete, syntheticFit("b.fit", 0L, 205), null, null, null, null, null);
		Upload secondDone = awaitTerminal(second.getId());

		assertThat(secondDone.getOverlapAction().toString()).isEqualTo("MARKED");
		verify(activityService, times(1))
				.recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
	}

	@Test
	void flagOnlyModeNeverTriggersTheCascade() throws Exception {
		User athlete = newAthlete("e2e-single-overlap-recompute-flag@example.cc", OverlapHandling.FLAG);
		Upload first = uploadService.createSingleUpload(
				athlete, syntheticFit("a.fit", 100L, 200), null, null, null, null, null);
		awaitTerminal(first.getId());
		Upload second = uploadService.createSingleUpload(
				athlete, syntheticFit("b.fit", 100L, 205), null, null, null, null, null);
		Upload secondDone = awaitTerminal(second.getId());

		assertThat(secondDone.getOverlapAction().toString()).isEqualTo("FLAGGED");
		verify(activityService, never()).recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
	}

	@Test
	void batchRunsTheCascadeExactlyOnceEvenWithTwoMarkedPairs() throws Exception {
		// Two independent overlapping pairs, far enough apart in start time that they can't
		// overlap each other - proves this isn't called once per marked file, only once for the
		// whole batch.
		User athlete = newAthlete("e2e-batch-overlap-recompute@example.cc", OverlapHandling.MARK);
		List<UploadIngestService.StagedFile> entries = List.of(
				new UploadIngestService.StagedFile("a1.fit", readBytes(syntheticFit("a1.fit", 0L, 200))),
				new UploadIngestService.StagedFile("b1.fit", readBytes(syntheticFit("b1.fit", 0L, 205))),
				new UploadIngestService.StagedFile("a2.fit", readBytes(syntheticFit("a2.fit", 10_000L, 200))),
				new UploadIngestService.StagedFile("b2.fit", readBytes(syntheticFit("b2.fit", 10_000L, 205))));
		UploadBatch batch = uploadService.createBatchUpload(athlete, entries, "dupes.zip", OnDuplicate.SKIP, OverlapHandling.MARK);

		List<Upload> children = awaitBatchTerminal(batch.getId(), 4);
		long marked = children.stream().filter(u -> u.getOverlapAction() != null
				&& "MARKED".equals(u.getOverlapAction().toString())).count();

		assertThat(marked).isEqualTo(2);
		verify(activityService, times(1))
				.recomputeAfterDuplicateChange(argThat(a -> a.getId().equals(athlete.getId())));
	}

	private static byte[] readBytes(MockMultipartFile file) throws Exception {
		return file.getBytes();
	}
}
