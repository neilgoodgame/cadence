package com.cadence.api.uploads.batch;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.support.JobOperatorFactoryBean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig {

	/** Auto-discovers every {@code Job} bean in the context - {@code TaskExecutorJobOperator} needs one to look jobs up by name. */
	@Bean
	public JobRegistry jobRegistry() {
		return new MapJobRegistry();
	}

	/**
	 * {@code start()} runs the whole job on the calling thread and blocks until it finishes
	 * ({@code SyncTaskExecutor}) - concurrency is controlled entirely by
	 * {@link UploadJobLauncher}'s own bounded dispatcher, not here.
	 *
	 * <p>Built via {@link JobOperatorFactoryBean} rather than {@code new TaskExecutorJobOperator()}
	 * + setters - that manual construction was verified (via
	 * {@code UploadConcurrencyIntegrationTest}, deliberately fired at real concurrency) to let
	 * {@code JobRepository} operations from concurrent {@code start()} calls interleave without
	 * a transaction boundary, cross-assigning {@code JobParameters} between executions. Spring
	 * Batch's own docs confirm {@code JobOperatorFactoryBean} exists specifically to wrap
	 * {@code TaskExecutorJobOperator} in a transactional proxy so every public method - including
	 * {@code start()} - runs inside one transaction; skipping it is what made concurrent launches
	 * unsafe, not (only) the {@code @JobScope}/{@code @StepScope} issue fixed in
	 * {@link UploadJobContext}.
	 */
	@Bean
	public JobOperatorFactoryBean syncJobOperator(JobRepository jobRepository, JobRegistry jobRegistry,
			PlatformTransactionManager transactionManager) {
		JobOperatorFactoryBean factory = new JobOperatorFactoryBean();
		factory.setJobRepository(jobRepository);
		factory.setJobRegistry(jobRegistry);
		factory.setTransactionManager(transactionManager);
		factory.setTaskExecutor(new SyncTaskExecutor());
		return factory;
	}
}
