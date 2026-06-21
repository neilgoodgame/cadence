package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadCalculations;
import com.cadence.api.uploads.UploadProcessingException;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.uploads.parsing.FileParserDispatcher;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@StepScope
public class ParseFileTasklet implements Tasklet {

	private static final Map<com.cadence.api.common.domain.Sport, String> SPORT_LABELS = Map.of(
			com.cadence.api.common.domain.Sport.BIKE, "Bike",
			com.cadence.api.common.domain.Sport.RUN, "Run",
			com.cadence.api.common.domain.Sport.SWIM, "Swim",
			com.cadence.api.common.domain.Sport.WALK, "Walk");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final UploadJobContext context;
	private final UploadRepository uploadRepository;
	private final ActivityRepository activityRepository;
	private final LapRepository lapRepository;
	private final CadenceProperties properties;

	public ParseFileTasklet(UploadJobContext context, UploadRepository uploadRepository, ActivityRepository activityRepository,
			LapRepository lapRepository, CadenceProperties properties) {
		this.context = context;
		this.uploadRepository = uploadRepository;
		this.activityRepository = activityRepository;
		this.lapRepository = lapRepository;
		this.properties = properties;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
		Upload upload = uploadRepository.findById(context.getUploadId())
				.orElseThrow(() -> new NotFoundException("No such upload."));
		upload.setStatus(UploadStatus.PROCESSING);
		upload.setProgress(0.0);
		uploadRepository.save(upload);

		ParsedActivity parsed;
		Path path = Path.of(properties.uploads().mediaRoot(), upload.getStoredPath());
		try (InputStream in = Files.newInputStream(path)) {
			parsed = FileParserDispatcher.parse(in, upload.getFilename());
		}
		catch (Exception e) {
			throw new UploadProcessingException("corrupt_file", e.getMessage());
		}
		if (parsed.samples().isEmpty()) {
			throw new UploadProcessingException("empty_file", "No samples found in file.");
		}

		Activity activity = new Activity();
		activity.setAthlete(upload.getAthlete());
		activity.setSport(parsed.sport());
		activity.setEnvironment(parsed.environment());
		activity.setHasGps(parsed.hasGps());
		activity.setName(SPORT_LABELS.getOrDefault(parsed.sport(), "Activity") + " on "
				+ DATE_FORMAT.format(parsed.startDate().atZone(ZoneOffset.UTC)));
		activity.setStartDate(parsed.startDate());
		activity.setSource(parsed.source() != null ? parsed.source() : "");
		activity.setMovingTime(UploadCalculations.movingTime(parsed.samples()));
		activity.setDistanceKm(UploadCalculations.totalDistanceKm(parsed.samples(), parsed.laps()));
		activity.setDistanceSource(parsed.distanceSource() != null
				? parsed.distanceSource()
				: (parsed.hasGps() ? DistanceSource.GPS : DistanceSource.MANUAL));
		activity.setAscent(UploadCalculations.totalAscent(parsed.samples()));
		activity.setStartWeightKg(upload.getWeightBeforeKg());
		activity.setEndWeightKg(upload.getWeightAfterKg());
		activity.setFluidsMl(upload.getFluidsMl());
		activity.setShoe(upload.getShoe());
		activityRepository.save(activity);

		for (ParsedActivity.LapSummary lapSummary : parsed.laps()) {
			Lap lap = new Lap();
			lap.setActivity(activity);
			lap.setIndex(lapSummary.index());
			lap.setDuration(lapSummary.duration());
			lap.setDistanceKm(lapSummary.distanceKm());
			lap.setAvgHr(lapSummary.avgHr());
			lap.setAvgPower(lapSummary.avgPower());
			lapRepository.save(lap);
		}

		context.setParsed(parsed);
		context.setActivityId(activity.getId());
		return RepeatStatus.FINISHED;
	}
}
