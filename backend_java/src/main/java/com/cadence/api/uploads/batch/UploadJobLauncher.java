package com.cadence.api.uploads.batch;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

/**
 * Launches one {@code processUploadJob} execution per file. {@code launch()} enqueues the work
 * and returns immediately so the HTTP response doesn't wait on it, but the actual job executions
 * run one at a time on a single persistent virtual-thread worker - {@code @JobScope}/
 * {@code @StepScope} beans (e.g. {@link UploadJobContext}) turned out not to be safely isolated
 * across genuinely concurrent {@code Job.execute()} calls on this Spring Batch version (verified
 * directly: concurrent uploads produced cross-contaminated {@code Record} rows and duplicate/
 * dropped launches). A single dedicated worker - not a throttled pool - guarantees at most one
 * job body ever runs at a time, which removes the race rather than narrowing its window. Each
 * file's pipeline runs in tens of milliseconds in testing, so serializing the queue is an
 * acceptable v1 tradeoff against revisiting this once Spring Batch's scoping is solid under
 * virtual-thread concurrency.
 */
@Component
public class UploadJobLauncher {

	private static final Logger log = LoggerFactory.getLogger(UploadJobLauncher.class);

	private final JobOperator syncJobOperator;
	private final Job processUploadJob;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

	public UploadJobLauncher(JobOperator syncJobOperator, Job processUploadJob) {
		this.syncJobOperator = syncJobOperator;
		this.processUploadJob = processUploadJob;
	}

	public void launch(String uploadId) {
		worker.submit(() -> {
			try {
				var params = new JobParametersBuilder()
						.addString("uploadId", uploadId)
						.addLong("launchedAt", System.currentTimeMillis())
						.toJobParameters();
				syncJobOperator.start(processUploadJob, params);
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
