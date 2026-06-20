package com.cadence.api.uploads.batch;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;

@Configuration
public class BatchConfig {

	/** Auto-discovers every {@code Job} bean in the context - {@code TaskExecutorJobOperator} needs one to look jobs up by name. */
	@Bean
	public JobRegistry jobRegistry() {
		return new MapJobRegistry();
	}

	/**
	 * Deliberately synchronous: {@code start()} runs the whole job on the calling thread and
	 * blocks until it finishes. Concurrency is instead controlled entirely by
	 * {@link UploadJobLauncher}'s own single-worker executor - see the comment there for why
	 * leaving any concurrency to this operator's TaskExecutor isn't safe on this Spring Batch
	 * version.
	 */
	@Bean
	public JobOperator syncJobOperator(JobRepository jobRepository, JobRegistry jobRegistry) throws Exception {
		TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
		operator.setJobRepository(jobRepository);
		operator.setJobRegistry(jobRegistry);
		operator.setTaskExecutor(new SyncTaskExecutor());
		operator.afterPropertiesSet();
		return operator;
	}
}
