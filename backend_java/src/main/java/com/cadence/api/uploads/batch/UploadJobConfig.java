package com.cadence.api.uploads.batch;

import javax.sql.DataSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires every {@code processUploadJob} step except {@code loadRecordsStep} - see
 * {@link UploadJobFactory} for why that one is built fresh per launch instead of as a singleton
 * {@code @Bean} here, and for {@code processUploadJob}'s own assembly (moved there for the same
 * reason: it composes {@code loadRecordsStep}).
 *
 * <p>Every step bean below is safe as an ordinary shared singleton: none of them hold
 * per-execution mutable instance state any more (each Tasklet resolves its
 * {@link UploadJobContext} explicitly via {@link UploadJobContextRegistry}, keyed by the
 * {@code uploadId} it reads out of {@code ChunkContext} on every call) - see
 * {@link UploadJobContext}'s Javadoc for why that replaced {@code @JobScope}/{@code @StepScope}.
 */
@Configuration
public class UploadJobConfig {

	@Bean
	public Step parseFileStep(JobRepository jobRepository, PlatformTransactionManager tm, ParseFileTasklet tasklet) {
		return new StepBuilder("parseFileStep", jobRepository).tasklet(tasklet, tm).build();
	}

	@Bean
	public Step computeDerivedStatsStep(JobRepository jobRepository, PlatformTransactionManager tm, ComputeDerivedStatsTasklet tasklet) {
		return new StepBuilder("computeDerivedStatsStep", jobRepository).tasklet(tasklet, tm).build();
	}

	@Bean
	public Step durationCurveStep(JobRepository jobRepository, PlatformTransactionManager tm, DurationCurveTasklet tasklet) {
		return new StepBuilder("durationCurveStep", jobRepository).tasklet(tasklet, tm).build();
	}

	@Bean
	public Step bestEffortStep(JobRepository jobRepository, PlatformTransactionManager tm, BestEffortTasklet tasklet) {
		return new StepBuilder("bestEffortStep", jobRepository).tasklet(tasklet, tm).build();
	}

	@Bean
	public Step workoutMatchStep(JobRepository jobRepository, PlatformTransactionManager tm, WorkoutMatchTasklet tasklet) {
		return new StepBuilder("workoutMatchStep", jobRepository).tasklet(tasklet, tm).build();
	}

	@Bean
	public Step finalizeUploadStep(JobRepository jobRepository, PlatformTransactionManager tm, FinalizeUploadTasklet tasklet) {
		return new StepBuilder("finalizeUploadStep", jobRepository).tasklet(tasklet, tm).build();
	}

	/** Stateless (see {@link RecordItemProcessor}) - safe as an ordinary shared singleton. */
	@Bean
	public ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> recordItemProcessor() {
		return new RecordItemProcessor();
	}

	/** Stateless wrapper around a shared {@link DataSource} - safe as an ordinary shared singleton. */
	@Bean
	public JdbcBatchItemWriter<RecordRow> recordItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<RecordRow>()
				.dataSource(dataSource)
				.sql("INSERT INTO record (activity_id, ts, t, power, heartrate, cadence, altitude, lat, lng, speed, distance_km, "
						+ "air_temp, humidity, core_temp, skin_temp, heat_strain) "
						+ "VALUES (:activityId, :ts, :t, :power, :heartrate, :cadence, :altitude, :lat, :lng, :speed, :distanceKm, "
						+ ":airTemp, :humidity, :coreTemp, :skinTemp, :heatStrain)")
				.beanMapped()
				.build();
	}
}
