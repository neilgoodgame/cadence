package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.batch.core.ExitStatus;
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
		// A batch child holding a metadata-stub FIT (no activity data) is settled as skipped
		// inside parseFileStep, which then exits NO_ACTIVITY_DATA; that routes straight to a
		// clean COMPLETED end so Spring Batch never logs it as a failure. The explicit FAILED
		// transition is load-bearing: with any explicit transition present, the on("*") branch
		// would otherwise match a failed parse too and run the rest of the job on it.
		return new JobBuilder("processUploadJob", jobRepository)
				.listener(listener)
				.start(parseFileStep)
				.on(ParseFileTasklet.EXIT_NO_ACTIVITY_DATA).end()
				.from(parseFileStep).on(ExitStatus.FAILED.getExitCode()).fail()
				.from(parseFileStep).on("*").to(loadRecordsStep)
				.next(computeDerivedStatsStep)
				.next(durationCurveStep)
				.next(bestEffortStep)
				.next(workoutMatchStep)
				.next(finalizeUploadStep)
				.end()
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
			ItemReader<RecordItemProcessor.SegmentSample> recordItemReader,
			ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> recordItemProcessor,
			ItemWriter<RecordRow> recordItemWriter) {
		return new StepBuilder("loadRecordsStep", jobRepository)
				.<RecordItemProcessor.SegmentSample, RecordRow>chunk(1000)
				.reader(recordItemReader)
				.processor(recordItemProcessor)
				.writer(recordItemWriter)
				.transactionManager(tm)
				.build();
	}

	@Bean
	@StepScope
	public ItemReader<RecordItemProcessor.SegmentSample> recordItemReader(UploadJobContext context) {
		List<RecordItemProcessor.SegmentSample> items = new ArrayList<>();
		for (UploadJobContext.Segment segment : context.getSegments()) {
			for (ParsedActivity.Sample sample : segment.parsed().samples()) {
				items.add(new RecordItemProcessor.SegmentSample(segment.activityId(), segment.parsed().startDate(), sample));
			}
		}
		return new ListItemReader<>(items);
	}

	@Bean
	@StepScope
	public ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> recordItemProcessor() {
		return new RecordItemProcessor();
	}

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
