package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadCalculations;
import com.cadence.api.uploads.UploadProcessingException;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.uploads.parsing.FileParserDispatcher;
import com.cadence.api.uploads.parsing.NoActivityDataException;
import com.cadence.api.uploads.parsing.ParsedActivity;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.ExitStatus;
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

	private static final Map<Sport, String> SPORT_LABELS = Map.of(
			Sport.BIKE, "Bike",
			Sport.RUN, "Run",
			Sport.SWIM, "Swim",
			Sport.WALK, "Walk",
			Sport.MULTISPORT, "Multisport",
			Sport.TRANSITION, "Transition");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	/** Step exit code routing metadata-stub files to the job's clean skip end (see UploadJobConfig). */
	static final String EXIT_NO_ACTIVITY_DATA = "NO_ACTIVITY_DATA";

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

		List<ParsedActivity> parsedActivities;
		Path path = Path.of(properties.uploads().mediaRoot(), upload.getStoredPath());
		try (InputStream in = Files.newInputStream(path)) {
			parsedActivities = FileParserDispatcher.parse(in, upload.getFilename());
		}
		catch (NoActivityDataException e) {
			// Garmin account exports mix metadata-stub FITs (no activity data) in with real
			// activities; a bulk import can hit these a thousand times over, so failing the job
			// for each would flood the logs with error traces. Settle the upload as skipped here
			// and steer the job to its clean skip end instead of through the failure machinery.
			// A deliberate single-file upload still fails loudly.
			if (upload.getBatch() != null) {
				upload.setStatus(UploadStatus.SKIPPED);
				upload.setCompletedAt(Instant.now());
				uploadRepository.save(upload);
				contribution.setExitStatus(new ExitStatus(EXIT_NO_ACTIVITY_DATA));
				return RepeatStatus.FINISHED;
			}
			throw new UploadProcessingException("no_activity_data", e.getMessage());
		}
		catch (Exception e) {
			throw new UploadProcessingException("corrupt_file", e.getMessage());
		}
		if (parsedActivities.get(0).samples().isEmpty()) {
			throw new UploadProcessingException("empty_file", "No samples found in file.");
		}

		// A multisport file arrives parent-first; every leg links back to the parent. The
		// parent carries the upload's weight/fluids while the shoe goes to the run/walk legs
		// it was actually worn for (a single-activity upload keeps all of them, as before).
		boolean multisport = parsedActivities.size() > 1;
		Activity parent = null;
		for (ParsedActivity parsed : parsedActivities) {
			boolean isChild = multisport && parent != null;
			Activity activity = new Activity();
			activity.setAthlete(upload.getAthlete());
			activity.setSport(parsed.sport());
			activity.setEnvironment(parsed.environment());
			activity.setHasGps(parsed.hasGps());
			activity.setName(SPORT_LABELS.getOrDefault(parsed.sport(), "Activity") + " on "
					+ DATE_FORMAT.format(parsed.startDate().atZone(ZoneOffset.UTC)));
			activity.setStartDate(parsed.startDate());
			activity.setSource(parsed.source() != null ? parsed.source() : "");
			activity.setDevice(parsed.device() != null ? parsed.device() : "");
			activity.setMovingTime(UploadCalculations.movingTime(parsed.samples()));
			activity.setDistanceKm(UploadCalculations.totalDistanceKm(parsed.samples(), parsed.laps()));
			activity.setDistanceSource(parsed.distanceSource() != null
					? parsed.distanceSource()
					: (parsed.hasGps() ? DistanceSource.GPS : DistanceSource.MANUAL));
			activity.setAscent(UploadCalculations.totalAscent(parsed.samples()));
			if (isChild) {
				activity.setParentActivity(parent);
			}
			else {
				activity.setStartWeightKg(upload.getWeightBeforeKg());
				activity.setEndWeightKg(upload.getWeightAfterKg());
				activity.setFluidsMl(upload.getFluidsMl());
			}
			boolean wearsShoe = multisport
					? (isChild && (parsed.sport() == Sport.RUN || parsed.sport() == Sport.WALK))
					: true;
			if (wearsShoe) {
				activity.setShoe(upload.getShoe());
			}
			activityRepository.save(activity);
			if (multisport && parent == null) {
				parent = activity;
			}

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

			context.addSegment(parsed, activity.getId());
		}
		return RepeatStatus.FINISHED;
	}
}
