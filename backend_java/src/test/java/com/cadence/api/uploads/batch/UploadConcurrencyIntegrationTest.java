package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.uploads.parsing.FitFileParser;
import com.cadence.api.uploads.parsing.ParsedActivity;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Regression test covering the concurrency bug described in {@link UploadJobContext}'s and
 * {@link UploadJobLauncher}'s Javadoc. {@link UploadJobLauncher} still serializes job execution
 * to one at a time - this test passes at that concurrency and is what verifies the
 * {@link UploadJobContextRegistry} and {@code JobOperatorFactoryBean} fixes actually work
 * correctly for a realistic batch size (12 uploads dispatched at once, queued through the same
 * single worker). It is deliberately also the reproduction case for the further, unresolved
 * issue documented on {@link UploadJobLauncher}: raising concurrency above 1 makes this test
 * fail again (cross-assigned {@code JobParameters} between concurrent executions), which is
 * exactly the signal to check before ever re-enabling it.
 */
class UploadConcurrencyIntegrationTest extends IntegrationTest {

	private static final int CONCURRENT_UPLOADS = 12;

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private LapRepository lapRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void concurrentUploadsDoNotCrossContaminateRecordsOrLaps() throws Exception {
		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		ParsedActivity expected = FitFileParser.parse(new ByteArrayInputStream(fitBytes)).get(0);
		int expectedSamples = expected.samples().size();
		int expectedLaps = expected.laps().size();
		assertThat(expectedSamples).isGreaterThan(0);

		// Distinct athletes, not just distinct uploads - dedupe-by-file-hash is scoped to
		// (athleteId, fileHash), so this guarantees none of the N uploads of the same file
		// content can ever be treated as a duplicate of another, regardless of how the
		// concurrent launches happen to interleave.
		List<User> athletes = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
			User user = new User();
			user.setEmail("concurrency-test-" + i + "@example.cc");
			user.setName("Concurrency Test " + i);
			user.setPassword("irrelevant-for-this-test");
			athletes.add(userRepository.save(user));
		}

		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_UPLOADS, Thread.ofVirtual().factory());
		CountDownLatch ready = new CountDownLatch(CONCURRENT_UPLOADS);
		CountDownLatch go = new CountDownLatch(1);
		List<String> uploadIds = Collections.synchronizedList(new ArrayList<>());
		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

		for (User athlete : athletes) {
			pool.submit(() -> {
				try {
					ready.countDown();
					go.await();
					MockMultipartFile file = new MockMultipartFile(
							"file", "running_outdoor_marathon.fit", "application/octet-stream", fitBytes);
					Upload upload = uploadService.createSingleUpload(athlete, file, null, null, null, null);
					uploadIds.add(upload.getId());
				}
				catch (Exception e) {
					errors.add(e);
				}
			});
		}
		ready.await();
		// Every thread is parked on the same latch before any of them calls createSingleUpload,
		// so releasing it fires all N as close to simultaneously as the JVM allows - not just
		// "eventually concurrent" but a genuine burst, matching the failure mode this guards.
		go.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		assertThat(errors).isEmpty();
		assertThat(uploadIds).hasSize(CONCURRENT_UPLOADS);

		awaitTerminal(uploadIds);

		List<Upload> finished = uploadRepository.findAllById(uploadIds);
		assertThat(finished).extracting(Upload::getStatus).allMatch(status -> status == UploadStatus.READY);

		for (Upload upload : finished) {
			Activity activity = activityRepository.findById(upload.getActivity().getId()).orElseThrow();
			Long recordCount = jdbcTemplate.queryForObject(
					"select count(*) from record where activity_id = ?", Long.class, activity.getId());
			int lapCount = lapRepository.findByActivityIdOrderByIndex(activity.getId()).size();

			assertThat(recordCount)
					.as("record rows for activity %s (upload %s)", activity.getId(), upload.getId())
					.isEqualTo(expectedSamples);
			assertThat(lapCount)
					.as("lap rows for activity %s (upload %s)", activity.getId(), upload.getId())
					.isEqualTo(expectedLaps);
		}

		// No activity's records leaked into another's: total rows across all N activities is
		// exactly N * expectedSamples, not more (duplicated across executions) or less (dropped).
		List<String> activityIds = finished.stream().map(u -> u.getActivity().getId()).collect(Collectors.toList());
		String placeholders = activityIds.stream().map(id -> "?").collect(Collectors.joining(","));
		Long totalRecords = jdbcTemplate.queryForObject(
				"select count(*) from record where activity_id in (" + placeholders + ")",
				Long.class, activityIds.toArray());
		assertThat(totalRecords).isEqualTo((long) expectedSamples * CONCURRENT_UPLOADS);
	}

	private void awaitTerminal(List<String> uploadIds) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
		while (System.currentTimeMillis() < deadline) {
			List<Upload> current = uploadRepository.findAllById(uploadIds);
			boolean allTerminal = current.size() == uploadIds.size() && current.stream()
					.allMatch(u -> u.getStatus() != UploadStatus.QUEUED && u.getStatus() != UploadStatus.PROCESSING);
			if (allTerminal) {
				return;
			}
			Thread.sleep(200);
		}
		throw new AssertionError("Timed out waiting for uploads to finish processing");
	}
}
