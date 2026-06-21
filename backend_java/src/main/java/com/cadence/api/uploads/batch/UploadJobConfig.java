package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class UploadJobConfig {

	@Bean
	public Job processUploadJob(JobRepository jobRepository, UploadJobExecutionListener listener,
			Step parseFileStep, Step loadRecordsStep, Step computeDerivedStatsStep, Step durationCurveStep,
			Step bestEffortStep, Step workoutMatchStep, Step finalizeUploadStep) {
		return new JobBuilder("processUploadJob", jobRepository)
				.listener(listener)
				.start(parseFileStep)
				.next(loadRecordsStep)
				.next(computeDerivedStatsStep)
				.next(durationCurveStep)
				.next(bestEffortStep)
				.next(workoutMatchStep)
				.next(finalizeUploadStep)
				.build();
	}

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

	@Bean
	public Step loadRecordsStep(JobRepository jobRepository, PlatformTransactionManager tm,
			ItemReader<ParsedActivity.Sample> recordItemReader, ItemProcessor<ParsedActivity.Sample, RecordRow> recordItemProcessor,
			ItemWriter<RecordRow> recordItemWriter) {
		return new StepBuilder("loadRecordsStep", jobRepository)
				.<ParsedActivity.Sample, RecordRow>chunk(1000)
				.reader(recordItemReader)
				.processor(recordItemProcessor)
				.writer(recordItemWriter)
				.transactionManager(tm)
				.build();
	}

	@Bean
	@StepScope
	public ItemReader<ParsedActivity.Sample> recordItemReader(UploadJobContext context) {
		return new ListItemReader<>(context.getParsed().samples());
	}

	@Bean
	@StepScope
	public ItemProcessor<ParsedActivity.Sample, RecordRow> recordItemProcessor(UploadJobContext context) {
		return new RecordItemProcessor(context.getActivityId(), context.getParsed().startDate());
	}

	@Bean
	public JdbcBatchItemWriter<RecordRow> recordItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<RecordRow>()
				.dataSource(dataSource)
				.sql("INSERT INTO record (activity_id, ts, t, power, heartrate, cadence, altitude, lat, lng, speed, distance_km) "
						+ "VALUES (:activityId, :ts, :t, :power, :heartrate, :cadence, :altitude, :lat, :lng, :speed, :distanceKm)")
				.beanMapped()
				.build();
	}
}
