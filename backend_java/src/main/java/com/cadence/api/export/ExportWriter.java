package com.cadence.api.export;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.LapMapper;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.StreamService;
import com.cadence.api.activities.dto.LapResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.export.dto.ActivityExportEntry;
import com.cadence.api.export.dto.ExportCounts;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.ComponentRepository;
import com.cadence.api.gear.GearMapper;
import com.cadence.api.gear.GearService;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.gear.ShoeService;
import com.cadence.api.gear.dto.BikeResponse;
import com.cadence.api.gear.dto.ComponentResponse;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.races.RaceService;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.SchedulingMapper;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutMapper;
import com.cadence.api.workouts.WorkoutRepository;
import com.cadence.api.workouts.dto.WorkoutDetailResponse;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonGenerator;

/**
 * Streams one athlete's full data export straight into the given generator, one section/record
 * at a time, so peak memory stays bounded to a single activity's laps+streams rather than the
 * athlete's whole history. Read-only and transactional so lazy associations (e.g. Shoe's model/
 * version, walked by ShoeService.toResponse) stay resolvable for the whole call - open-in-view is
 * off, so without this boundary those would throw once touched outside a session.
 *
 * <p>{@code entityManager.clear()} after every activity is load-bearing, not cosmetic: Hibernate's
 * persistence context pins every entity it loads for the life of the transaction, so without this
 * a large account (thousands of activities, millions of {@code Record} rows) fills the identity
 * map and OOMs well before the JSON/gzip side of this class ever sees that much data. Detaching
 * per-activity is safe because every field touched on an already-processed (or not-yet-processed)
 * Activity afterward is either a plain column or a lazy association read via {@code .getId()} only
 * (see ActivityService.toResponse / SchedulingMapper), which Hibernate resolves off the proxy
 * itself without needing the owning session.
 */
@Component
public class ExportWriter {

	// Persisting after every single item would mean one DB write per row in a multi-thousand-
	// activity export - throttle to every 20 items (still gives a smooth-looking bar).
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

		void tick(int n) {
			for (int i = 0; i < n; i++) {
				tick();
			}
		}

		void flush() {
			onProgress.accept(count);
		}
	}

	private final ActivityRepository activityRepository;
	private final ActivityService activityService;
	private final LapRepository lapRepository;
	private final LapMapper lapMapper;
	private final StreamService streamService;
	private final RaceRepository raceRepository;
	private final RaceService raceService;
	private final WorkoutRepository workoutRepository;
	private final WorkoutMapper workoutMapper;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final SchedulingMapper schedulingMapper;
	private final GearService gearService;
	private final ShoeService shoeService;
	private final BikeRepository bikeRepository;
	private final ShoeRepository shoeRepository;
	private final ComponentRepository componentRepository;
	private final GearMapper gearMapper;
	private final EntityManager entityManager;

	public ExportWriter(ActivityRepository activityRepository, ActivityService activityService, LapRepository lapRepository,
			LapMapper lapMapper, StreamService streamService, RaceRepository raceRepository, RaceService raceService,
			WorkoutRepository workoutRepository, WorkoutMapper workoutMapper,
			ScheduledWorkoutRepository scheduledWorkoutRepository, SchedulingMapper schedulingMapper,
			GearService gearService, ShoeService shoeService, BikeRepository bikeRepository, ShoeRepository shoeRepository,
			ComponentRepository componentRepository, GearMapper gearMapper, EntityManager entityManager) {
		this.activityRepository = activityRepository;
		this.activityService = activityService;
		this.lapRepository = lapRepository;
		this.lapMapper = lapMapper;
		this.streamService = streamService;
		this.raceRepository = raceRepository;
		this.raceService = raceService;
		this.workoutRepository = workoutRepository;
		this.workoutMapper = workoutMapper;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.schedulingMapper = schedulingMapper;
		this.gearService = gearService;
		this.shoeService = shoeService;
		this.bikeRepository = bikeRepository;
		this.shoeRepository = shoeRepository;
		this.componentRepository = componentRepository;
		this.gearMapper = gearMapper;
		this.entityManager = entityManager;
	}

	/** {@code sportFilter} narrows activities/races/workouts/scheduled_workouts to one discipline;
	 * {@code null} exports everything, unchanged from before this filter existed. Equipment is
	 * always exported in full either way - it's small, and shoes have no sport of their own to
	 * filter by.
	 *
	 * <p>Annotated independently rather than just delegating to the 4-arg overload below: a
	 * same-class call to that overload wouldn't go through this bean's transactional proxy
	 * (Spring AOP self-invocation), silently dropping {@code @Transactional} and breaking every
	 * lazy association this class relies on being resolvable - caught by
	 * ExportWriterIntegrationTest failing with LazyInitializationException the first time this
	 * was missed. */
	@Transactional(readOnly = true)
	public void write(String athleteId, Sport sportFilter, JsonGenerator generator) {
		write(athleteId, sportFilter, generator, step -> { });
	}

	/** As {@link #write(String, Sport, JsonGenerator)}, but calls {@code onStep} with one of
	 * "equipment"/"workouts"/"activities"/"races"/"scheduled_workouts" right as each section
	 * starts - what a progress dialog polls ExportJob.currentStep for. {@code onStep} is expected
	 * to persist independently of this method's own transaction (see ExportProgressUpdater) - it
	 * must not be assumed to see the writes this transaction hasn't committed yet, or vice versa. */
	@Transactional(readOnly = true)
	public void write(String athleteId, Sport sportFilter, JsonGenerator generator, Consumer<String> onStep) {
		write(athleteId, sportFilter, generator, onStep, total -> { }, processed -> { });
	}

	/** As {@link #write(String, Sport, JsonGenerator, Consumer)}, but also turns onStep's coarse
	 * per-section signal into a fine-grained item-level progress bar *scoped to the current
	 * section* - e.g. while onStep is "activities", onTotal/onProgress report activities
	 * specifically, not a blend across every kind of data. {@code onTotal} fires once per
	 * section, right after onStep, with that section's own count from computeCounts (the same
	 * cheap count queries the "counts" file field already computes - so this is free, no second
	 * query); {@code onProgress} fires with a running count *reset to 0 at the start of each
	 * section* as each item in it is written, throttled (see Progress) so a multi-thousand-row
	 * export doesn't turn into a DB write per row. "equipment" covers bikes+shoes+components
	 * combined (one section/one onStep call), so its total is the sum of those three. Same
	 * persist-independently contract as onStep. */
	@Transactional(readOnly = true)
	public void write(String athleteId, Sport sportFilter, JsonGenerator generator, Consumer<String> onStep,
			IntConsumer onTotal, IntConsumer onProgress) {
		generator.writeStartObject();
		generator.writeStringProperty("generated_at", Instant.now().toString());
		generator.writeStringProperty("athlete_id", athleteId);
		ExportCounts counts = computeCounts(athleteId, sportFilter);
		generator.writePOJOProperty("counts", counts);

		// Equipment and workouts come before activities (which reference bike/shoe/workout ids),
		// and activities come before races/scheduled_workouts (which reference activity ids) -
		// dependency order, so a forward-only streaming importer can resolve id references in one
		// pass without having to look ahead. See ImportReader for the reader side of this contract.
		Progress progress = startSection(onStep, onTotal, onProgress, "equipment",
				(int) (counts.bikes() + counts.shoes() + counts.components()));
		writeEquipment(athleteId, generator, progress);
		progress.flush();

		progress = startSection(onStep, onTotal, onProgress, "workouts", (int) counts.workouts());
		writeWorkouts(athleteId, sportFilter, generator, progress);
		progress.flush();

		progress = startSection(onStep, onTotal, onProgress, "activities", (int) counts.activities());
		writeActivities(athleteId, sportFilter, generator, progress);
		progress.flush();

		progress = startSection(onStep, onTotal, onProgress, "races", (int) counts.races());
		writeRaces(athleteId, sportFilter, generator, progress);
		progress.flush();

		progress = startSection(onStep, onTotal, onProgress, "scheduled_workouts", (int) counts.scheduledWorkouts());
		writeScheduledWorkouts(athleteId, sportFilter, generator, progress);
		progress.flush();

		generator.writeEndObject();
	}

	private static Progress startSection(
			Consumer<String> onStep, IntConsumer onTotal, IntConsumer onProgress, String step, int total) {
		onStep.accept(step);
		onTotal.accept(total);
		return new Progress(onProgress);
	}

	/** Cheap upfront count queries (no row materialization) for the "counts" metadata block at
	 * the top of the file - the same filtered scope each writeX method below uses. */
	private ExportCounts computeCounts(String athleteId, Sport sportFilter) {
		long activities = sportFilter == null ? activityRepository.countByAthleteId(athleteId)
				: activityRepository.countByAthleteIdAndSport(athleteId, sportFilter);
		long races = sportFilter == null ? raceRepository.countByAthleteId(athleteId)
				: raceRepository.countByAthleteIdAndEffectiveSport(athleteId, sportFilter);
		long workouts = sportFilter == null ? workoutRepository.countByCreatedById(athleteId)
				: workoutRepository.countByCreatedByIdAndSport(athleteId, sportFilter);
		long scheduledWorkouts = sportFilter == null ? scheduledWorkoutRepository.countByAthleteId(athleteId)
				: scheduledWorkoutRepository.countByAthleteIdAndWorkoutSport(athleteId, sportFilter);
		// Equipment is always exported in full, regardless of the sport filter - it's small, and
		// shoes have no sport of their own to filter by.
		return new ExportCounts(activities, races, workouts, scheduledWorkouts, bikeRepository.countByAthleteId(athleteId),
				shoeRepository.countByAthleteIdAndRetiredFalse(athleteId), componentRepository.countByBikeAthleteId(athleteId));
	}

	private void writeActivities(String athleteId, Sport sportFilter, JsonGenerator generator, Progress progress) {
		// A multisport parent's own sport is MULTISPORT, not e.g. BIKE, so filtering by sport here
		// naturally excludes multisport parents while still including any matching individual legs -
		// no special-case code needed. A leg whose parent got excluded this way just carries a
		// parent_activity_id ImportReader won't find in this file; its deferred-link resolution
		// already handles that (skips the link rather than failing).
		List<Activity> activities = sportFilter == null
				? activityRepository.findByAthleteIdOrderByStartDate(athleteId)
				: activityRepository.findByAthleteIdAndSportOrderByStartDate(athleteId, sportFilter);

		generator.writeArrayPropertyStart("activities");
		for (Activity activity : activities) {
			List<LapResponse> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId()).stream()
					.map(lapMapper::toResponse)
					.toList();
			// "high" resolution == no downsampling (StreamService.getStreams step=1 default) - a
			// full data export shouldn't quietly thin out the athlete's own recorded data.
			generator.writePOJO(new ActivityExportEntry(
					activityService.toResponse(activity).withoutTransientThresholdState(), laps,
					streamService.getStreams(activity, null, "high")));
			entityManager.clear();
			progress.tick();
		}
		generator.writeEndArray();
	}

	private void writeRaces(String athleteId, Sport sportFilter, JsonGenerator generator, Progress progress) {
		generator.writeArrayPropertyStart("races");
		for (var race : raceRepository.findByAthleteIdOrderByDateAsc(athleteId)) {
			// A race's own sport can be null even when it's linked to an activity - linking to
			// an *existing* race (as opposed to "mark this activity as a race", which sets sport
			// at creation) never backfills it. Fall back to the linked activity's sport so a
			// sport-scoped export doesn't silently drop these. A race with no sport recorded and
			// no linked activity to infer one from is still excluded under a filter - ambiguous
			// otherwise.
			Sport effectiveSport = race.getSport() != null ? race.getSport()
					: race.getActivity() != null ? race.getActivity().getSport() : null;
			if (sportFilter == null || sportFilter.equals(effectiveSport)) {
				generator.writePOJO(raceService.toResponse(race));
				progress.tick();
			}
		}
		generator.writeEndArray();
	}

	private void writeWorkouts(String athleteId, Sport sportFilter, JsonGenerator generator, Progress progress) {
		generator.writeArrayPropertyStart("workouts");
		for (Workout workout : workoutRepository.findByCreatedByIdOrderByIdDesc(athleteId)) {
			if (sportFilter == null || sportFilter.equals(workout.getSport())) {
				generator.writePOJO(new WorkoutDetailResponse(
						workoutMapper.toResponse(workout), workoutMapper.toStepTree(workout.getSteps())));
				progress.tick();
			}
		}
		generator.writeEndArray();
	}

	private void writeScheduledWorkouts(String athleteId, Sport sportFilter, JsonGenerator generator, Progress progress) {
		generator.writeArrayPropertyStart("scheduled_workouts");
		for (var scheduled : scheduledWorkoutRepository.findByAthleteIdOrderByDate(athleteId)) {
			if (sportFilter == null || sportFilter.equals(scheduled.getWorkout().getSport())) {
				generator.writePOJO(schedulingMapper.toResponse(scheduled));
				progress.tick();
			}
		}
		generator.writeEndArray();
	}

	private void writeEquipment(String athleteId, JsonGenerator generator, Progress progress) {
		List<Bike> bikes = gearService.listBikes(athleteId);

		generator.writeObjectPropertyStart("equipment");

		List<BikeResponse> bikeResponses = bikes.stream().map(gearService::toBikeResponse).toList();
		generator.writePOJOProperty("bikes", bikeResponses);
		progress.tick(bikeResponses.size());

		// Shoe/Component have no retired flag of their own to filter here; listShoes already
		// excludes retired shoes (findByAthleteIdAndRetiredFalseOrderByIdDesc) - matches the
		// "active gear only" scope for this export.
		List<ShoeResponse> shoeResponses = shoeService.listShoes(athleteId).stream().map(shoeService::toResponse).toList();
		generator.writePOJOProperty("shoes", shoeResponses);
		progress.tick(shoeResponses.size());

		List<ComponentResponse> componentResponses = bikes.stream()
				.flatMap(bike -> componentRepository.findByBikeId(bike.getId()).stream())
				.map(gearMapper::toResponse)
				.toList();
		generator.writePOJOProperty("components", componentResponses);
		progress.tick(componentResponses.size());

		generator.writeEndObject();
	}
}
