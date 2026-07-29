package com.cadence.api.uploads.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds a fresh {@code processUploadJob} {@link Job} for every {@link UploadJobLauncher#launch}
 * call, rather than wiring one singleton {@code Job} bean shared across every upload the way
 * {@code UploadJobConfig}'s other steps are. It's the only step that genuinely needs a new
 * instance per launch: {@code loadRecordsStep}'s {@link RecordItemReader} materializes its
 * backing list from {@link UploadJobContextRegistry} on first {@code read()}, and needs to know
 * which upload's segments to read - a plain constructor argument on a freshly-built reader does
 * that with no scoping mechanism involved at all, unlike the {@code @StepScope} proxy this
 * replaced (see {@link UploadJobContext}'s Javadoc for the concurrency bug that motivated this).
 *
 * <p>Building a small, cheap object graph (one {@code Step}, one {@code Job}) per launch is
 * negligible overhead next to the actual parse-and-persist work each job does.
 */
@Component
public class UploadJobFactory {

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final UploadJobContextRegistry contextRegistry;
	private final UploadJobExecutionListener listener;
	private final Step parseFileStep;
	private final Step computeDerivedStatsStep;
	private final Step durationCurveStep;
	private final Step bestEffortStep;
	private final Step workoutMatchStep;
	private final Step finalizeUploadStep;
	private final ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> recordItemProcessor;
	private final ItemWriter<RecordRow> recordItemWriter;

	public UploadJobFactory(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			UploadJobContextRegistry contextRegistry, UploadJobExecutionListener listener, Step parseFileStep,
			Step computeDerivedStatsStep, Step durationCurveStep, Step bestEffortStep, Step workoutMatchStep,
			Step finalizeUploadStep, ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> recordItemProcessor,
			ItemWriter<RecordRow> recordItemWriter) {
		this.jobRepository = jobRepository;
		this.transactionManager = transactionManager;
		this.contextRegistry = contextRegistry;
		this.listener = listener;
		this.parseFileStep = parseFileStep;
		this.computeDerivedStatsStep = computeDerivedStatsStep;
		this.durationCurveStep = durationCurveStep;
		this.bestEffortStep = bestEffortStep;
		this.workoutMatchStep = workoutMatchStep;
		this.finalizeUploadStep = finalizeUploadStep;
		this.recordItemProcessor = recordItemProcessor;
		this.recordItemWriter = recordItemWriter;
	}

	public Job buildJob(String uploadId) {
		Step loadRecordsStep = new StepBuilder("loadRecordsStep", jobRepository)
				.<RecordItemProcessor.SegmentSample, RecordRow>chunk(1000)
				.reader(new RecordItemReader(contextRegistry, uploadId))
				.processor(recordItemProcessor)
				.writer(recordItemWriter)
				.transactionManager(transactionManager)
				.build();

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
}
