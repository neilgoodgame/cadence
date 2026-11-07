package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.UnprocessableEntityException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** Issue #501 (phase 1, part 5): mirrors backend's activities/tests/test_unmerge_service.py. */
class ActivityUnmergeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityMergeService mergeService;
	@Autowired
	private ActivityUnmergeService unmergeService;
	@Autowired
	private UploadService uploadService;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private MergeRepository mergeRepository;
	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	/** A real, ingested-from-a-file activity - unlike a hand-built one, this has a genuine
	 * Upload row with a stored path, so undoMerge can actually re-parse it. */
	private Activity uploadedTarget(User athlete) throws Exception {
		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader()
				.getResourceAsStream("fit-fixtures/indoor_ride_zwift_core_sensor.fit")) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		MockMultipartFile file = new MockMultipartFile(
				"file", "indoor_ride_zwift_core_sensor.fit", "application/octet-stream", fitBytes);
		Upload upload = uploadService.createSingleUpload(athlete, file, null, null, null, null, null);
		return awaitReadyActivity(upload.getId());
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

	private Activity sourceWithRecords(User athlete, Instant start, int count, int power) {
		Activity source = new Activity();
		source.setAthlete(athlete);
		source.setSport(Sport.BIKE);
		source.setName("Source");
		source.setStartDate(start);
		source = activityRepository.save(source);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(source.getId(), start.plusSeconds(t)));
			record.setActivity(source);
			record.setT(t);
			record.setPower(power);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return source;
	}

	@Test
	void restoresStreamAndActivityLevelFieldsAndMarksTheMergeUndone() throws Exception {
		User athlete = newAthlete("unmerge-restore@example.cc");
		Activity target = uploadedTarget(athlete);
		Integer originalAvgPower = target.getAvgPower();
		String originalName = target.getName();
		Activity source = sourceWithRecords(athlete, target.getStartDate(), 999, 500);

		ActivityMergeService.MergeResult result = mergeService.runMerge(target.getId(), source.getId(),
				List.of("power", "name"), NullPolicy.OVERWRITE, 0, false, false);
		Activity merged = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(merged.getAvgPower()).isNotEqualTo(originalAvgPower);
		assertThat(merged.getName()).isEqualTo("Source");

		unmergeService.undoMerge(target.getId());

		Activity restored = activityRepository.findById(target.getId()).orElseThrow();
		assertThat(restored.getAvgPower()).isEqualTo(originalAvgPower);
		assertThat(restored.getName()).isEqualTo(originalName);
		Merge merge = mergeRepository.findById(result.mergeId()).orElseThrow();
		assertThat(merge.getUndoneAt()).isNotNull();
	}

	@Test
	void unhidesTheSourceWhenTheMergeHadHiddenIt() throws Exception {
		User athlete = newAthlete("unmerge-unhide@example.cc");
		Activity target = uploadedTarget(athlete);
		Activity source = sourceWithRecords(athlete, target.getStartDate(), 999, 500);

		mergeService.runMerge(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0, true, false);
		Activity hiddenSource = activityRepository.findById(source.getId()).orElseThrow();
		assertThat(hiddenSource.getPrimaryActivity()).isNotNull();

		unmergeService.undoMerge(target.getId());

		Activity unhiddenSource = activityRepository.findById(source.getId()).orElseThrow();
		assertThat(unhiddenSource.getPrimaryActivity()).isNull();
	}

	@Test
	void throwsWhenThereIsNoActiveMerge() throws Exception {
		User athlete = newAthlete("unmerge-no-merge@example.cc");
		Activity target = uploadedTarget(athlete);

		assertThatThrownBy(() -> unmergeService.undoMerge(target.getId()))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void throwsWhenTheTargetHasNoStoredUploadFile() {
		User athlete = newAthlete("unmerge-no-file@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity target = sourceWithRecords(athlete, start, 5, 100);
		target.setName("No file");
		activityRepository.save(target);
		Activity source = sourceWithRecords(athlete, start, 5, 250);

		mergeService.runMerge(target.getId(), source.getId(), List.of("power"), NullPolicy.OVERWRITE, 0, false, false);

		assertThatThrownBy(() -> unmergeService.undoMerge(target.getId()))
				.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void blocksUndoWhenMoreThanOneMergeIsActive() throws Exception {
		User athlete = newAthlete("unmerge-chain@example.cc");
		Activity target = uploadedTarget(athlete);
		Activity sourceA = sourceWithRecords(athlete, target.getStartDate(), 999, 500);
		Activity sourceB = sourceWithRecords(athlete, target.getStartDate(), 999, 600);

		mergeService.runMerge(target.getId(), sourceA.getId(), List.of("power"), NullPolicy.OVERWRITE, 0, false, false);
		mergeService.runMerge(target.getId(), sourceB.getId(), List.of("power"), NullPolicy.OVERWRITE, 0, false, false);

		assertThatThrownBy(() -> unmergeService.undoMerge(target.getId()))
				.isInstanceOf(UnprocessableEntityException.class);
	}
}
