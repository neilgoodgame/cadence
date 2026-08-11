package com.cadence.api.imports;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.TagService;
import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.LapResponse;
import com.cadence.api.activities.dto.StreamsResponse;
import com.cadence.api.export.dto.ActivityExportEntry;
import com.cadence.api.export.dto.ExportCounts;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.GearService;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeService;
import com.cadence.api.gear.dto.BikeCreateRequest;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.ComponentCreateRequest;
import com.cadence.api.gear.dto.ComponentResponse;
import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.imports.dto.ImportCounts;
import com.cadence.api.races.RaceService;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.SchedulingService;
import com.cadence.api.scheduling.dto.ScheduledWorkoutResponse;
import com.cadence.api.uploads.batch.RecordRow;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutService;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutDetailResponse;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one athlete's exported data file back in, creating brand-new rows with fresh ids (an
 * import never updates or matches existing data - see the plan doc for why).
 *
 * <p>Deliberately not one big {@code @Transactional} method: that's exactly the mistake that
 * OOM'd {@code ExportWriter} on the read side (see its Javadoc) - here on the write side, holding
 * thousands of newly-{@code @Transactional}-managed entities across one long transaction would
 * fail the same way. Every item (bike/shoe/component/workout/activity/race/scheduled workout)
 * commits in its own short transaction via {@link #transactionTemplate} instead, both to bound
 * memory and so one bad item can be skipped without losing everything already imported.
 *
 * <p>{@code Record} rows never become JPA entities at all - {@link #recordItemWriter} is the same
 * raw-SQL bean the upload pipeline uses ({@code UploadJobConfig.recordItemWriter}), reused as-is.
 *
 * <p>Cross-reference remapping: {@code ExportWriter} deliberately emits equipment and workouts
 * before activities, and activities before races/scheduled_workouts, so this single forward
 * streaming pass can resolve old-id -> new-entity references as it goes. The one exception is
 * Activity.parent_activity_id/primary_activity_id, which points at another activity that may
 * appear later in the same array - those are queued into {@link #deferredActivityLinks} and
 * resolved in a final cheap pass once every activity has a new id.
 */
@Component
public class ImportReader {

	private static final Logger log = LoggerFactory.getLogger(ImportReader.class);

	private final GearService gearService;
	private final ShoeService shoeService;
	private final ShoeModelRepository shoeModelRepository;
	private final ShoeModelVersionRepository shoeModelVersionRepository;
	private final WorkoutService workoutService;
	private final RaceService raceService;
	private final SchedulingService schedulingService;
	private final TagService tagService;
	private final ActivityRepository activityRepository;
	private final LapRepository lapRepository;
	private final JdbcBatchItemWriter<RecordRow> recordItemWriter;
	private final JsonMapper jsonMapper;
	private final UserRepository userRepository;
	private final TransactionTemplate transactionTemplate;

	public ImportReader(GearService gearService, ShoeService shoeService, ShoeModelRepository shoeModelRepository,
			ShoeModelVersionRepository shoeModelVersionRepository, WorkoutService workoutService, RaceService raceService,
			SchedulingService schedulingService, TagService tagService, ActivityRepository activityRepository,
			LapRepository lapRepository, JdbcBatchItemWriter<RecordRow> recordItemWriter, JsonMapper jsonMapper,
			UserRepository userRepository, PlatformTransactionManager transactionManager) {
		this.gearService = gearService;
		this.shoeService = shoeService;
		this.shoeModelRepository = shoeModelRepository;
		this.shoeModelVersionRepository = shoeModelVersionRepository;
		this.workoutService = workoutService;
		this.raceService = raceService;
		this.schedulingService = schedulingService;
		this.tagService = tagService;
		this.activityRepository = activityRepository;
		this.lapRepository = lapRepository;
		this.recordItemWriter = recordItemWriter;
		this.jsonMapper = jsonMapper;
		this.userRepository = userRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	private record DeferredActivityLink(String newChildId, String oldTargetId, boolean isParent) {
	}

	private static final class Counts {
		int activities;
		int races;
		int workouts;
		int scheduledWorkouts;
		int bikes;
		int shoes;
		int components;
		int skipped;
	}

	// Persisting after every single item would mean one DB write per row in a multi-thousand-
	// activity import - throttle to every 20 items (still gives a smooth-looking bar).
	private static final int PROGRESS_INTERVAL = 20;

	/** Wraps an onProgress callback with throttling. {@link #flush()} bypasses the throttle -
	 * called at section boundaries so the persisted count never drifts far behind reality
	 * (otherwise a section with, say, 7 items never fires at all, since 7 % 20 != 0). */
	private static final class Progress {
		private final IntConsumer onProgress;
		private int count;

		Progress(IntConsumer onProgress) {
			this.onProgress = onProgress;
		}

		void tick() {
			count++;
			if (count % PROGRESS_INTERVAL == 0) {
				onProgress.accept(count);
			}
		}

		void flush() {
			onProgress.accept(count);
		}
	}

	public ImportCounts read(String athleteId, Path file) throws IOException {
		return read(athleteId, file, step -> { }, total -> { }, processed -> { });
	}

	/** As {@link #read(String, Path)}, but calls {@code onStep} with one of "equipment"/
	 * "workouts"/"activities"/"races"/"scheduled_workouts" right as each section starts - what a
	 * progress dialog polls ImportJob.currentStep for. */
	public ImportCounts read(String athleteId, Path file, Consumer<String> onStep) throws IOException {
		return read(athleteId, file, onStep, total -> { }, processed -> { });
	}

	/** As {@link #read(String, Path, Consumer)}, but also turns onStep's coarse per-section signal
	 * into a fine-grained item-level progress bar: {@code onTotal} fires once, by reading the
	 * source file's own "counts" metadata block (see ExportWriter.computeCounts) - a file exported
	 * before that field existed has no "counts" key, in which case onTotal is simply never called.
	 * {@code onProgress} fires with a running cumulative item count (imported or skipped - either
	 * way the reader has moved past it) as each item across every section is processed, throttled
	 * (see Progress). Unlike ExportWriter, neither callback here needs its own REQUIRES_NEW
	 * transaction to be visible to a polling request - this method isn't itself wrapped in one
	 * long transaction (see the class Javadoc: it opens many independent short ones via
	 * transactionTemplate), so a plain save commits on its own already. */
	public ImportCounts read(String athleteId, Path file, Consumer<String> onStep, IntConsumer onTotal,
			IntConsumer onProgress) throws IOException {
		// A JPA reference, not a loaded entity - deliberately, so it's safe to reuse across the
		// many independent short transactions this method opens (see the class Javadoc).
		User athlete = userRepository.getReferenceById(athleteId);
		Counts counts = new Counts();
		Progress progress = new Progress(onProgress);
		Map<String, Bike> bikesByOldId = new HashMap<>();
		Map<String, Shoe> shoesByOldId = new HashMap<>();
		Map<String, Workout> workoutsByOldId = new HashMap<>();
		Map<String, String> activityIdMap = new HashMap<>();
		List<DeferredActivityLink> deferredLinks = new ArrayList<>();

		try (JsonParser parser = jsonMapper.createParser(
				new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
			parser.nextToken(); // START_OBJECT
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				String field = parser.currentName();
				parser.nextToken();
				switch (field) {
					case "counts" -> {
						ExportCounts fileCounts = parser.readValueAs(ExportCounts.class);
						onTotal.accept((int) (fileCounts.activities() + fileCounts.races() + fileCounts.workouts()
								+ fileCounts.scheduledWorkouts() + fileCounts.bikes() + fileCounts.shoes()
								+ fileCounts.components()));
					}
					case "equipment" -> {
						onStep.accept("equipment");
						importEquipment(parser, athlete, bikesByOldId, shoesByOldId, counts, progress);
						progress.flush();
					}
					case "workouts" -> {
						onStep.accept("workouts");
						importWorkouts(parser, athlete, workoutsByOldId, counts, progress);
						progress.flush();
					}
					case "activities" -> {
						onStep.accept("activities");
						importActivities(parser, athlete, workoutsByOldId, bikesByOldId, shoesByOldId, activityIdMap,
								deferredLinks, counts, progress);
						progress.flush();
					}
					case "races" -> {
						onStep.accept("races");
						importRaces(parser, athlete, activityIdMap, counts, progress);
						progress.flush();
					}
					case "scheduled_workouts" -> {
						onStep.accept("scheduled_workouts");
						importScheduledWorkouts(parser, athlete, workoutsByOldId, activityIdMap, counts, progress);
						progress.flush();
					}
					default -> parser.skipChildren(); // generated_at, athlete_id - scalars, nothing to walk
				}
			}
		}

		resolveDeferredLinks(deferredLinks, activityIdMap);

		return new ImportCounts(counts.activities, counts.races, counts.workouts, counts.scheduledWorkouts,
				counts.bikes, counts.shoes, counts.components, counts.skipped);
	}

	private void importEquipment(JsonParser parser, User athlete, Map<String, Bike> bikesByOldId,
			Map<String, Shoe> shoesByOldId, Counts counts, Progress progress) {
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			String field = parser.currentName();
			parser.nextToken();
			switch (field) {
				case "bikes" -> {
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						BikeResponse br = parser.readValueAs(BikeResponse.class);
						try {
							Bike created = transactionTemplate.execute(status -> gearService.createBike(
									athlete, new BikeCreateRequest(br.name(), br.kind(), br.groupset(), br.distanceKm())));
							bikesByOldId.put(br.id(), created);
							counts.bikes++;
						}
						catch (Exception e) {
							counts.skipped++;
							log.warn("Skipped bike import (old id {}): {}", br.id(), e);
						}
						progress.tick();
					}
				}
				case "shoes" -> {
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						ShoeResponse sr = parser.readValueAs(ShoeResponse.class);
						try {
							Shoe created = transactionTemplate.execute(status -> {
								ShoeModelVersion smv = findOrCreateShoeModelVersion(sr.manufacturer(), sr.model(), sr.version());
								// km (accumulated usage) isn't part of ShoeCreateRequest - createShoe always starts a
								// shoe at 0km, same as a brand-new shoe created through the regular UI would.
								return shoeService.createShoe(athlete,
										new ShoeCreateRequest(smv.getId(), sr.colourway(), sr.name(), sr.limitKm(), sr.image()));
							});
							shoesByOldId.put(sr.id(), created);
							counts.shoes++;
						}
						catch (Exception e) {
							// Most commonly a same-name-shoe ConflictException on a re-import - skip, don't abort.
							counts.skipped++;
							log.warn("Skipped shoe import (old id {}): {}", sr.id(), e);
						}
						progress.tick();
					}
				}
				case "components" -> {
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						ComponentResponse cr = parser.readValueAs(ComponentResponse.class);
						Bike bike = bikesByOldId.get(cr.bikeId());
						if (bike == null) {
							counts.skipped++;
							progress.tick();
							continue;
						}
						try {
							transactionTemplate.executeWithoutResult(status -> gearService.createComponent(
									bike, new ComponentCreateRequest(cr.name(), cr.limitKm(), cr.km(), cr.model())));
							counts.components++;
						}
						catch (Exception e) {
							counts.skipped++;
							log.warn("Skipped component import (old id {}): {}", cr.id(), e);
						}
						progress.tick();
					}
				}
				default -> parser.skipChildren();
			}
		}
	}

	private ShoeModelVersion findOrCreateShoeModelVersion(String manufacturer, String model, String version) {
		ShoeModel shoeModel = shoeModelRepository.findFirstByManufacturerAndModel(manufacturer, model)
				.orElseGet(() -> {
					ShoeModel created = new ShoeModel();
					created.setManufacturer(manufacturer);
					created.setModel(model);
					return shoeModelRepository.save(created);
				});
		return shoeModelVersionRepository.findFirstByShoeModelIdAndVersion(shoeModel.getId(), version)
				.orElseGet(() -> {
					ShoeModelVersion created = new ShoeModelVersion();
					created.setShoeModel(shoeModel);
					created.setVersion(version);
					return shoeModelVersionRepository.save(created);
				});
	}

	private void importWorkouts(
			JsonParser parser, User athlete, Map<String, Workout> workoutsByOldId, Counts counts, Progress progress) {
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			WorkoutDetailResponse detail = parser.readValueAs(WorkoutDetailResponse.class);
			try {
				// folderId is deliberately null - folders aren't part of the export, and
				// WorkoutService.resolveFolder 404s on an id that doesn't exist for this athlete.
				// duration/tss/chartPreview aren't restored either - createWorkout recomputes them
				// from the step tree, same as the original creation did.
				Workout created = transactionTemplate.execute(status -> workoutService.createWorkout(athlete,
						new WorkoutCreateRequest(detail.workout().name(), detail.workout().sport(), detail.steps(), null,
								detail.workout().tags())));
				workoutsByOldId.put(detail.workout().id(), created);
				counts.workouts++;
			}
			catch (Exception e) {
				counts.skipped++;
				log.warn("Skipped workout import (old id {}): {}", detail.workout().id(), e);
			}
			progress.tick();
		}
	}

	private void importActivities(JsonParser parser, User athlete, Map<String, Workout> workoutsByOldId,
			Map<String, Bike> bikesByOldId, Map<String, Shoe> shoesByOldId, Map<String, String> activityIdMap,
			List<DeferredActivityLink> deferredLinks, Counts counts, Progress progress) {
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			ActivityExportEntry entry = parser.readValueAs(ActivityExportEntry.class);
			try {
				String newId = transactionTemplate.execute(status -> {
					Activity activity = buildActivity(entry.activity(), athlete, workoutsByOldId, bikesByOldId, shoesByOldId);
					activity = activityRepository.save(activity);
					// recordItemWriter below is raw JDBC on this same connection/transaction - Hibernate
					// defers the actual INSERT for the row above until flush, so without this the record
					// rows' FK to activity_id fails: the activity doesn't physically exist yet as far as
					// the connection is concerned (found the hard way against a 2600-activity account).
					activityRepository.flush();

					for (LapResponse lap : entry.laps()) {
						Lap l = new Lap();
						l.setActivity(activity);
						l.setIndex(lap.index());
						l.setDuration(lap.duration());
						l.setDistanceKm(lap.distanceKm());
						l.setAvgHr(lap.avgHr());
						l.setAvgPower(lap.avgPower());
						lapRepository.save(l);
					}

					List<RecordRow> rows = buildRecordRows(activity, entry.streams());
					if (!rows.isEmpty()) {
						try {
							recordItemWriter.write(new Chunk<>(rows));
						}
						catch (Exception e) {
							throw new RuntimeException("Failed writing records", e);
						}
					}

					for (String tagName : entry.activity().tags()) {
						tagService.attachTag(activity, athlete, null, tagName);
					}
					return activity.getId();
				});

				activityIdMap.put(entry.activity().id(), newId);
				if (entry.activity().parentActivityId() != null) {
					deferredLinks.add(new DeferredActivityLink(newId, entry.activity().parentActivityId(), true));
				}
				if (entry.activity().primaryActivityId() != null) {
					deferredLinks.add(new DeferredActivityLink(newId, entry.activity().primaryActivityId(), false));
				}
				counts.activities++;
			}
			catch (Exception e) {
				counts.skipped++;
				log.warn("Skipped activity import (old id {}): {}", entry.activity().id(), e);
			}
			progress.tick();
		}
	}

	private Activity buildActivity(ActivityResponse ar, User athlete, Map<String, Workout> workoutsByOldId,
			Map<String, Bike> bikesByOldId, Map<String, Shoe> shoesByOldId) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(ar.sport());
		activity.setEnvironment(ar.environment());
		activity.setHasGps(ar.hasGps());
		activity.setName(ar.name());
		activity.setStartDate(ar.startDate());
		activity.setSource(ar.source());
		activity.setDevice(ar.device());
		activity.setMovingTime(ar.movingTime());
		activity.setDistanceKm(ar.distanceKm());
		activity.setDistanceSource(ar.distanceSource());
		activity.setAvgPower(ar.avgPower());
		activity.setNormPower(ar.normPower());
		activity.setIntensity(ar.intensity());
		activity.setTss(ar.tss());
		activity.setAvgHr(ar.avgHr());
		activity.setMaxHr(ar.maxHr());
		activity.setAscent(ar.ascent());
		activity.setMaxPower(ar.maxPower());
		activity.setAvgCadence(ar.avgCadence());
		activity.setMaxCadence(ar.maxCadence());
		activity.setMaxSpeed(ar.maxSpeed());
		activity.setTotalDescent(ar.totalDescent());
		activity.setElevationMin(ar.elevationMin());
		activity.setElevationMax(ar.elevationMax());
		activity.setCalories(ar.calories());
		activity.setTrimp(ar.trimp());
		activity.setAvgLeftBalancePct(ar.avgLeftBalancePct());
		activity.setStartWeightKg(ar.startWeightKg());
		activity.setEndWeightKg(ar.endWeightKg());
		activity.setFluidsMl(ar.fluidsMl());
		activity.setAvgAirTemp(ar.avgAirTemp());
		activity.setAvgHumidity(ar.avgHumidity());
		activity.setAerobicTrainingEffect(ar.aerobicTrainingEffect());
		activity.setAnaerobicTrainingEffect(ar.anaerobicTrainingEffect());
		activity.setTrainingEffectLabel(ar.trainingEffectLabel());
		if (ar.workoutId() != null) {
			activity.setWorkout(workoutsByOldId.get(ar.workoutId()));
		}
		if (ar.bikeId() != null) {
			activity.setBike(bikesByOldId.get(ar.bikeId()));
		}
		if (ar.shoeId() != null) {
			activity.setShoe(shoesByOldId.get(ar.shoeId()));
		}
		return activity;
	}

	/**
	 * Reconstructs per-second Record rows from the export's parallel-array stream shape.
	 * Everything except {@code latlng} aligns 1:1 by index with {@code time} - {@code latlng}
	 * drops points with no GPS fix and carries no index/time marker of its own (see
	 * StreamService), so pairing it against the first N time entries is the closest reconstruction
	 * possible without changing that shape; this can drift for activities with GPS dropouts
	 * (accepted, documented limitation - see the plan doc).
	 */
	@SuppressWarnings("unchecked")
	private List<RecordRow> buildRecordRows(Activity activity, StreamsResponse streams) {
		Map<String, Object> fields = streams.fields();
		List<Number> time = (List<Number>) fields.get("time");
		if (time == null || time.isEmpty()) {
			return List.of();
		}
		List<List<Number>> latlng = (List<List<Number>>) fields.get("latlng");
		List<Number> power = (List<Number>) fields.get("power");
		List<Number> heartrate = (List<Number>) fields.get("heartrate");
		List<Number> cadence = (List<Number>) fields.get("cadence");
		List<Number> altitude = (List<Number>) fields.get("altitude");
		List<Number> distance = (List<Number>) fields.get("distance");
		List<Number> speed = (List<Number>) fields.get("speed");
		List<Number> airTemp = (List<Number>) fields.get("air_temp");
		List<Number> humidity = (List<Number>) fields.get("humidity");
		List<Number> coreTemp = (List<Number>) fields.get("core_temp");
		List<Number> skinTemp = (List<Number>) fields.get("skin_temp");
		List<Number> heatStrain = (List<Number>) fields.get("heat_strain");

		List<RecordRow> rows = new ArrayList<>(time.size());
		for (int i = 0; i < time.size(); i++) {
			int t = time.get(i).intValue();
			Double lat = null;
			Double lng = null;
			if (latlng != null && i < latlng.size()) {
				List<Number> pair = latlng.get(i);
				lat = pair.get(0) != null ? pair.get(0).doubleValue() : null;
				lng = pair.get(1) != null ? pair.get(1).doubleValue() : null;
			}
			rows.add(new RecordRow(
					activity.getId(),
					Timestamp.from(activity.getStartDate().plusSeconds(t)),
					t,
					intAt(power, i), intAt(heartrate, i), intAt(cadence, i),
					dblAt(altitude, i), lat, lng, dblAt(speed, i), dblAt(distance, i),
					dblAt(airTemp, i), intAt(humidity, i), dblAt(coreTemp, i), dblAt(skinTemp, i), dblAt(heatStrain, i)));
		}
		return rows;
	}

	private static Integer intAt(List<Number> list, int i) {
		if (list == null || i >= list.size() || list.get(i) == null) {
			return null;
		}
		return list.get(i).intValue();
	}

	private static Double dblAt(List<Number> list, int i) {
		if (list == null || i >= list.size() || list.get(i) == null) {
			return null;
		}
		return list.get(i).doubleValue();
	}

	private void resolveDeferredLinks(List<DeferredActivityLink> links, Map<String, String> activityIdMap) {
		for (DeferredActivityLink link : links) {
			String newTargetId = activityIdMap.get(link.oldTargetId());
			if (newTargetId == null) {
				continue; // the referenced activity wasn't part of this import
			}
			transactionTemplate.executeWithoutResult(status -> {
				Activity child = activityRepository.getReferenceById(link.newChildId());
				Activity target = activityRepository.getReferenceById(newTargetId);
				if (link.isParent()) {
					child.setParentActivity(target);
				}
				else {
					child.setPrimaryActivity(target);
				}
				activityRepository.save(child);
			});
		}
	}

	private void importRaces(
			JsonParser parser, User athlete, Map<String, String> activityIdMap, Counts counts, Progress progress) {
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			RaceResponse rr = parser.readValueAs(RaceResponse.class);
			try {
				String newActivityId = rr.activityId() != null ? activityIdMap.get(rr.activityId()) : null;
				transactionTemplate.executeWithoutResult(status -> raceService.createRace(athlete,
						new RaceCreateRequest(rr.name(), rr.date(), rr.sport() == null || rr.sport().isBlank() ? null : rr.sport(),
								rr.distanceKm(), rr.goalTime(), rr.resultTime(), newActivityId, rr.url(), rr.resultsUrl(),
								rr.notes())));
				counts.races++;
			}
			catch (Exception e) {
				counts.skipped++;
				log.warn("Skipped race import", e);
			}
			progress.tick();
		}
	}

	private void importScheduledWorkouts(JsonParser parser, User athlete, Map<String, Workout> workoutsByOldId,
			Map<String, String> activityIdMap, Counts counts, Progress progress) {
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			ScheduledWorkoutResponse sr = parser.readValueAs(ScheduledWorkoutResponse.class);
			Workout workout = workoutsByOldId.get(sr.workoutId());
			if (workout == null) {
				counts.skipped++;
				progress.tick();
				continue;
			}
			try {
				transactionTemplate.executeWithoutResult(status -> {
					ScheduledWorkout scheduled = schedulingService.schedule(
							athlete.getId(), workout.getId(), athlete.getId(), sr.date(), sr.timeOfDay());
					String newActivityId = sr.activityId() != null ? activityIdMap.get(sr.activityId()) : null;
					// A MISSED original status has no direct settable path via SchedulingService - it
					// comes back PLANNED, a minor accepted gap (see plan doc).
					if (newActivityId != null) {
						schedulingService.update(scheduled, null, newActivityId);
					}
				});
				counts.scheduledWorkouts++;
			}
			catch (Exception e) {
				counts.skipped++;
				log.warn("Skipped scheduled workout import", e);
			}
			progress.tick();
		}
	}
}
