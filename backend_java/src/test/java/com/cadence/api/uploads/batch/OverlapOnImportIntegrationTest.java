package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.OverlapAction;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.OnDuplicate;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadBatch;
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

/**
 * End-to-end coverage for issue #498's on-ingest side: {@code UploadController}'s {@code
 * on_overlap} param -> {@code UploadService.createBatchUpload} resolving it against the
 * athlete's default -> {@code ParseFileTasklet} running {@code ActivityOverlapDetectionService}
 * for each newly-created activity -> the result landing on the {@code Upload} row. The detection
 * and primary-selection logic itself is covered directly (and far more cheaply) by {@code
 * ActivityOverlapDetectionServiceIntegrationTest}; this only exists to prove the wiring between
 * the HTTP-adjacent layer and that service actually happens.
 */
class OverlapOnImportIntegrationTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;

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

	/** Same start time and elapsed time on both files - the simplest possible full overlap - so
	 * this doesn't depend on reasoning about the FIT SDK's DateTime unit for an exact offset. */
	private byte[] syntheticFit(int manufacturer, String name) throws Exception {
		Path path = tempDir.resolve(name);
		FileEncoder encoder = new FileEncoder(path.toFile(), Fit.ProtocolVersion.V2_0);
		FileIdMesg fileId = new FileIdMesg();
		fileId.setType(File.ACTIVITY);
		fileId.setManufacturer(manufacturer);
		fileId.setTimeCreated(new DateTime(0L));
		encoder.write(fileId);

		SessionMesg session = new SessionMesg();
		session.setSport(Sport.CYCLING);
		session.setStartTime(new DateTime(0L));
		session.setTotalElapsedTime(3600.0f);
		encoder.write(session);

		RecordMesg r1 = new RecordMesg();
		r1.setTimestamp(new DateTime(0L));
		r1.setPower(200);
		encoder.write(r1);
		RecordMesg r2 = new RecordMesg();
		r2.setTimestamp(new DateTime(1L));
		r2.setPower(205);
		encoder.write(r2);
		encoder.close();
		return Files.readAllBytes(path);
	}

	private MockMultipartFile zipOf(String zwiftName, String garminName) throws Exception {
		byte[] zwiftFit = syntheticFit(Manufacturer.ZWIFT, "zwift-source.fit");
		byte[] garminFit = syntheticFit(Manufacturer.GARMIN, "garmin-source.fit");
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry(zwiftName));
			zip.write(zwiftFit);
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry(garminName));
			zip.write(garminFit);
			zip.closeEntry();
		}
		return new MockMultipartFile("file", "duplicates.zip", "application/zip", zipBytes.toByteArray());
	}

	private List<Upload> awaitTerminal(String batchId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			List<Upload> children = uploadService.getBatchChildren(batchId);
			if (children.size() == 2 && children.stream()
					.allMatch(u -> u.getStatus() != UploadStatus.QUEUED && u.getStatus() != UploadStatus.PROCESSING)) {
				return children;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Timed out waiting for the batch to finish processing");
	}

	@Test
	void onOverlapMarkOnTheRequestMarksTheGarminCopyAsADuplicateOfTheZwiftCopy() throws Exception {
		// The athlete's own default is OFF - the request's on_overlap must be what actually
		// drives this, not a coincidentally-matching default.
		User athlete = newAthlete("e2e-overlap-mark@example.cc", OverlapHandling.OFF);
		MockMultipartFile zip = zipOf("zwift.fit", "garmin.fit");

		UploadBatch batch = uploadService.createBatchUpload(athlete, zip, OnDuplicate.SKIP, OverlapHandling.MARK);
		assertThat(batch.getOverlapHandling()).isEqualTo(OverlapHandling.MARK);

		List<Upload> children = awaitTerminal(batch.getId());
		assertThat(children).extracting(Upload::getStatus).allMatch(status -> status == UploadStatus.READY);

		List<Activity> activities = children.stream()
				.map(u -> activityRepository.findById(u.getActivity().getId()).orElseThrow())
				.toList();
		Activity zwiftActivity = activities.stream().filter(a -> "Zwift".equals(a.getDevice())).findFirst().orElseThrow();
		Activity garminActivity = activities.stream().filter(a -> "Garmin".equals(a.getDevice())).findFirst().orElseThrow();

		assertThat(garminActivity.getPrimaryActivity()).isNotNull();
		assertThat(garminActivity.getPrimaryActivity().getId()).isEqualTo(zwiftActivity.getId());
		assertThat(zwiftActivity.getPrimaryActivity()).isNull();

		// Exactly one of the two uploads is the one that ran detection *after* the other
		// activity already existed - whichever file the batch happened to process second.
		List<Upload> withAction = children.stream().filter(u -> u.getOverlapAction() != null).toList();
		assertThat(withAction).hasSize(1);
		assertThat(withAction.get(0).getOverlapAction()).isEqualTo(OverlapAction.MARKED);
		assertThat(withAction.get(0).getOverlapOtherActivityId()).isNotNull();
	}

	@Test
	void onOverlapOffOnTheRequestSkipsDetectionEntirelyEvenWhenTheAthleteDefaultIsMark() throws Exception {
		User athlete = newAthlete("e2e-overlap-off-override@example.cc", OverlapHandling.MARK);
		MockMultipartFile zip = zipOf("zwift.fit", "garmin.fit");

		UploadBatch batch = uploadService.createBatchUpload(athlete, zip, OnDuplicate.SKIP, OverlapHandling.OFF);
		assertThat(batch.getOverlapHandling()).isEqualTo(OverlapHandling.OFF);

		List<Upload> children = awaitTerminal(batch.getId());
		for (Upload upload : children) {
			assertThat(upload.getOverlapAction()).isNull();
			Activity activity = activityRepository.findById(upload.getActivity().getId()).orElseThrow();
			assertThat(activity.getPrimaryActivity()).isNull();
		}
	}

	@Test
	void noRequestOverrideFallsBackToTheAthletesOwnOverlapHandlingDefault() throws Exception {
		User athlete = newAthlete("e2e-overlap-athlete-default@example.cc", OverlapHandling.FLAG);
		MockMultipartFile zip = zipOf("zwift.fit", "garmin.fit");

		// No on_overlap passed at all (null) - mirrors the controller's optional @RequestParam,
		// and is exactly what GarminSyncService's scheduled-sync call site also passes.
		UploadBatch batch = uploadService.createBatchUpload(athlete, zip, OnDuplicate.SKIP, null);
		assertThat(batch.getOverlapHandling()).isEqualTo(OverlapHandling.FLAG);

		List<Upload> children = awaitTerminal(batch.getId());
		List<Upload> withAction = children.stream().filter(u -> u.getOverlapAction() != null).toList();
		assertThat(withAction).hasSize(1);
		// FLAG never writes to the activity, even though a pair was found.
		assertThat(withAction.get(0).getOverlapAction()).isEqualTo(OverlapAction.FLAGGED);
		for (Upload upload : children) {
			Activity activity = activityRepository.findById(upload.getActivity().getId()).orElseThrow();
			assertThat(activity.getPrimaryActivity()).isNull();
		}
	}
}
