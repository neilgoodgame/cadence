package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.OverlapAction;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.Upload;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Same wiring as {@code OverlapOnImportIntegrationTest}, for the single (non-batch) {@code
 * createSingleUpload} path - a pre-existing gap this issue's own "on_overlap on the single-file
 * upload endpoint" item closes: a lone file had no way to carry a per-request override at all,
 * always falling back to the athlete's {@code overlapHandling} default.
 */
class OverlapOnSingleUploadIntegrationTest extends IntegrationTest {

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

	/** Same start time and elapsed time on both files - the simplest possible full overlap. */
	private MockMultipartFile syntheticFit(String name, int power) throws Exception {
		Path path = tempDir.resolve(name);
		FileEncoder encoder = new FileEncoder(path.toFile(), Fit.ProtocolVersion.V2_0);
		FileIdMesg fileId = new FileIdMesg();
		fileId.setType(File.ACTIVITY);
		fileId.setManufacturer(Manufacturer.GARMIN);
		fileId.setTimeCreated(new DateTime(0L));
		encoder.write(fileId);

		SessionMesg session = new SessionMesg();
		session.setSport(Sport.CYCLING);
		session.setStartTime(new DateTime(0L));
		session.setTotalElapsedTime(3600.0f);
		encoder.write(session);

		RecordMesg record = new RecordMesg();
		record.setTimestamp(new DateTime(0L));
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

	@Test
	void onOverlapMarkOnTheRequestMarksTheNewUploadAgainstAnExistingActivity() throws Exception {
		// The athlete's own default is OFF - the request's onOverlap must be what actually
		// drives this, not a coincidentally-matching default.
		User athlete = newAthlete("e2e-single-overlap-mark@example.cc", OverlapHandling.OFF);
		Upload first = uploadService.createSingleUpload(athlete, syntheticFit("a.fit", 200), null, null, null, null, null);
		Upload firstDone = awaitTerminal(first.getId());
		assertThat(firstDone.getStatus()).isEqualTo(UploadStatus.READY);
		String firstActivityId = firstDone.getActivity().getId();

		Upload second = uploadService.createSingleUpload(
				athlete, syntheticFit("b.fit", 205), null, null, null, null, OverlapHandling.MARK);
		Upload secondDone = awaitTerminal(second.getId());

		assertThat(secondDone.getOverlapAction()).isEqualTo(OverlapAction.MARKED);
		assertThat(secondDone.getOverlapOtherActivityId()).isEqualTo(firstActivityId);

		Activity firstActivity = activityRepository.findById(firstActivityId).orElseThrow();
		Activity secondActivity = activityRepository.findById(secondDone.getActivity().getId()).orElseThrow();
		assertThat(secondActivity.getPrimaryActivity()).isNotNull();
		assertThat(secondActivity.getPrimaryActivity().getId()).isEqualTo(firstActivity.getId());
		assertThat(firstActivity.getPrimaryActivity()).isNull();
	}

	@Test
	void noRequestOverrideFallsBackToTheAthletesOwnOverlapHandlingDefault() throws Exception {
		User athlete = newAthlete("e2e-single-overlap-default@example.cc", OverlapHandling.MARK);
		Upload first = uploadService.createSingleUpload(athlete, syntheticFit("a.fit", 200), null, null, null, null, null);
		awaitTerminal(first.getId());

		// No onOverlap passed at all (null) - mirrors the controller's optional @RequestParam.
		Upload second = uploadService.createSingleUpload(athlete, syntheticFit("b.fit", 205), null, null, null, null, null);
		Upload secondDone = awaitTerminal(second.getId());

		assertThat(secondDone.getOverlapAction()).isEqualTo(OverlapAction.MARKED);
		Activity secondActivity = activityRepository.findById(secondDone.getActivity().getId()).orElseThrow();
		assertThat(secondActivity.getPrimaryActivity()).isNotNull();
	}

	@Test
	void onOverlapOffOnTheRequestOverridesAnAthleteDefaultOfMark() throws Exception {
		User athlete = newAthlete("e2e-single-overlap-off-override@example.cc", OverlapHandling.MARK);
		Upload first = uploadService.createSingleUpload(athlete, syntheticFit("a.fit", 200), null, null, null, null, null);
		awaitTerminal(first.getId());

		Upload second = uploadService.createSingleUpload(
				athlete, syntheticFit("b.fit", 205), null, null, null, null, OverlapHandling.OFF);
		Upload secondDone = awaitTerminal(second.getId());

		assertThat(secondDone.getOverlapAction()).isNull();
		Activity secondActivity = activityRepository.findById(secondDone.getActivity().getId()).orElseThrow();
		assertThat(secondActivity.getPrimaryActivity()).isNull();
	}
}
