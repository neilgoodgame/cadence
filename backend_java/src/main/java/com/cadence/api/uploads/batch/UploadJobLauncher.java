package com.cadence.api.uploads.batch;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

/**
 * Launches one {@code processUploadJob} execution per file. {@code launch()} enqueues the work
 * and returns immediately so the HTTP response doesn't wait on it, but the actual job executions
 * still run one at a time on a single persistent virtual-thread worker.
 *
 * <p>This was revisited to try to safely enable real concurrency, on the theory that the
 * original single-worker design was just working around fixable bugs rather than a hard
 * limitation. Two real, independent bugs WERE found and fixed along the way:
 *
 * <ol>
 *   <li>{@code UploadJobContext} used to be a {@code @JobScope} bean resolved via Spring Batch's
 *       thread-local {@code JobSynchronizationManager}; every step now resolves it explicitly
 *       from {@link UploadJobContextRegistry} (a plain thread-safe map keyed by {@code uploadId})
 *       instead. See {@link UploadJobContext}'s Javadoc.
 *   <li>{@code BatchConfig}'s {@code syncJobOperator} used to construct
 *       {@code TaskExecutorJobOperator} manually, skipping the transactional proxy Spring
 *       Batch's own docs say is required (via {@code JobOperatorFactoryBean}) for {@code start()}
 *       to be safe under concurrent callers. See {@code BatchConfig}'s Javadoc.
 * </ol>
 *
 * <p>Both fixes are real and are kept. But {@code UploadConcurrencyIntegrationTest} - which
 * fires many uploads at genuinely the same time and checks every one lands the exact right,
 * uncontaminated data - still fails with both fixes in place and {@code MAX_CONCURRENT_JOBS}
 * raised above 1: {@code JobParameters} intermittently get cross-assigned between concurrent
 * {@code start()} calls (one upload launched twice with another upload's parameters, a
 * different upload never launched at all), a different symptom shape each run - consistent with
 * a genuine race, not a deterministic logic bug, and not one either fix above resolves. That
 * points at something deeper in this exact Spring Batch 6.0.4 release's {@code JobRepository}/
 * {@code JobExplorer} internals that wasn't identified with confidence. Forcing concurrency
 * through anyway, against a real failing regression test, would be irresponsible - so this stays
 * serialized (proven safe by the same test, at {@code MAX_CONCURRENT_JOBS = 1}) until that's
 * actually root-caused, e.g. after a Spring Batch version bump. If revisiting: start by running
 * {@code UploadConcurrencyIntegrationTest} with concurrency raised again - it will fail exactly
 * the same way if the underlying issue is still present.
 */
@Component
public class UploadJobLauncher {

	private static final Logger log = LoggerFactory.getLogger(UploadJobLauncher.class);

	private final JobOperator syncJobOperator;
	private final UploadJobFactory jobFactory;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

	public UploadJobLauncher(JobOperator syncJobOperator, UploadJobFactory jobFactory) {
		this.syncJobOperator = syncJobOperator;
		this.jobFactory = jobFactory;
	}

	public void launch(String uploadId) {
		worker.submit(() -> {
			try {
				var params = new JobParametersBuilder()
						.addString("uploadId", uploadId)
						.addLong("launchedAt", System.currentTimeMillis())
						.toJobParameters();
				syncJobOperator.start(jobFactory.buildJob(uploadId), params);
			}
			catch (Exception e) {
				log.error("Failed to launch upload-processing job for upload {}", uploadId, e);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		worker.shutdown();
	}
}
