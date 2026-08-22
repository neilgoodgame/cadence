package com.cadence.api.uploads.batch;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.calc.EnvironmentSanitizer;
import com.cadence.api.activities.calc.RunningPowerSanitizer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ParseFileTasklet implements Tasklet {

	private static final Map<Sport, String> SPORT_LABELS = Map.of(
			Sport.BIKE, "Bike",
			Sport.RUN, "Run",
			Sport.SWIM, "Swim",
			Sport.WALK, "Walk",
			Sport.ROW, "Row",
			Sport.MULTISPORT, "Multisport",
			Sport.TRANSITION, "Transition");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	/** Step exit code routing metadata-stub files to the job's clean skip end (see UploadJobConfig). */
	static final String EXIT_NO_ACTIVITY_DATA = "NO_ACTIVITY_DATA";

	private final UploadJobContextRegistry contextRegistry;
	private final UploadRepository uploadRepository;
	private final ActivityRepository activityRepository;
	private final LapRepository lapRepository;
	private final CadenceProperties properties;

	public ParseFileTasklet(UploadJobContextRegistry contextRegistry, UploadRepository uploadRepository,
			ActivityRepository activityRepository, LapRepository lapRepository, CadenceProperties properties) {
		this.contextRegistry = contextRegistry;
		this.uploadRepository = uploadRepository;
		this.activityRepository = activityRepository;
		this.lapRepository = lapRepository;
		this.properties = properties;
	}

	@Override
	@Transactional
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
		String uploadId = chunkContext.getStepContext().getStepExecution().getJobParameters().getString("uploadId");
		UploadJobContext context = contextRegistry.forUpload(uploadId);
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
		for (ParsedActivity rawParsed : parsedActivities) {
			// Dropped here, before a Record is ever written, rather than left for a later
			// recompute to clean up - see RunningPowerSanitizer's Javadoc for the failure mode
			// (an implausible single-sample power spike a footpod occasionally emits).
			ParsedActivity parsed = sanitizeEnvironment(sanitizeRunningPower(rawParsed, upload.getAthlete().getMaxRunningPowerWatts()));
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
			activity.setTotalDescent(UploadCalculations.totalDescent(parsed.samples()));
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

	/** Applies {@link RunningPowerSanitizer}'s ceiling to the in-memory samples before a Record row,
	 * lap average, or any ingest-time derived stat is built from them - see its Javadoc for the
	 * failure mode. Note this doesn't touch {@code parsed.laps()}' own avgPower (computed upstream
	 * in FitFileParser from the unsanitized samples) - a lap containing a spike still reports a
	 * skewed average; only the per-second series consumers (best efforts, duration curves,
	 * normalized power, threshold history, the activity chart) are protected. */
	private static ParsedActivity sanitizeRunningPower(ParsedActivity parsed, int maxRunningPowerWatts) {
		if (parsed.sport() != Sport.RUN) {
			return parsed;
		}
		List<Integer> sanitizedPower = RunningPowerSanitizer.sanitize(
				parsed.samples().stream().map(ParsedActivity.Sample::power).toList(), parsed.sport(), maxRunningPowerWatts);
		List<ParsedActivity.Sample> samples = new ArrayList<>(parsed.samples().size());
		for (int i = 0; i < parsed.samples().size(); i++) {
			ParsedActivity.Sample s = parsed.samples().get(i);
			samples.add(new ParsedActivity.Sample(s.t(), s.lat(), s.lng(), s.altitude(), s.distanceKm(),
					s.heartrate(), s.cadence(), sanitizedPower.get(i), s.speed(), s.airTemp(), s.humidity(),
					s.coreTemp(), s.skinTemp(), s.heatStrain(), s.leftBalancePct()));
		}
		return new ParsedActivity(parsed.sport(), parsed.environment(), parsed.hasGps(), parsed.startDate(),
				parsed.source(), parsed.device(), parsed.distanceSource(), samples, parsed.laps(),
				parsed.aerobicTrainingEffect(), parsed.anaerobicTrainingEffect());
	}

	/** Applies {@link EnvironmentSanitizer} to the in-memory samples before a Record row or any
	 * ingest-time derived stat (avg air temp/humidity) is built from them - see its Javadoc for
	 * the failure mode (a Stryd ambient-sensor pairing failure reporting a flat 0°C/0% for an
	 * entire activity instead of omitting the reading). */
	private static ParsedActivity sanitizeEnvironment(ParsedActivity parsed) {
		if (parsed.sport() != Sport.RUN) {
			return parsed;
		}
		List<Integer> humiditySeries = parsed.samples().stream().map(ParsedActivity.Sample::humidity).toList();
		List<Double> sanitizedAirTemp = EnvironmentSanitizer.sanitizeAirTemp(
				parsed.samples().stream().map(ParsedActivity.Sample::airTemp).toList(), humiditySeries);
		List<Integer> sanitizedHumidity = EnvironmentSanitizer.sanitizeHumidity(humiditySeries);
		List<ParsedActivity.Sample> samples = new ArrayList<>(parsed.samples().size());
		for (int i = 0; i < parsed.samples().size(); i++) {
			ParsedActivity.Sample s = parsed.samples().get(i);
			samples.add(new ParsedActivity.Sample(s.t(), s.lat(), s.lng(), s.altitude(), s.distanceKm(),
					s.heartrate(), s.cadence(), s.power(), s.speed(), sanitizedAirTemp.get(i), sanitizedHumidity.get(i),
					s.coreTemp(), s.skinTemp(), s.heatStrain(), s.leftBalancePct()));
		}
		return new ParsedActivity(parsed.sport(), parsed.environment(), parsed.hasGps(), parsed.startDate(),
				parsed.source(), parsed.device(), parsed.distanceSource(), samples, parsed.laps(),
				parsed.aerobicTrainingEffect(), parsed.anaerobicTrainingEffect());
	}
}
