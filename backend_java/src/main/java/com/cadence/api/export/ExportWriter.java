package com.cadence.api.export;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.LapMapper;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.StreamService;
import com.cadence.api.activities.dto.LapResponse;
import com.cadence.api.export.dto.ActivityExportEntry;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.ComponentRepository;
import com.cadence.api.gear.GearMapper;
import com.cadence.api.gear.GearService;
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
	private final ComponentRepository componentRepository;
	private final GearMapper gearMapper;
	private final EntityManager entityManager;

	public ExportWriter(ActivityRepository activityRepository, ActivityService activityService, LapRepository lapRepository,
			LapMapper lapMapper, StreamService streamService, RaceRepository raceRepository, RaceService raceService,
			WorkoutRepository workoutRepository, WorkoutMapper workoutMapper,
			ScheduledWorkoutRepository scheduledWorkoutRepository, SchedulingMapper schedulingMapper,
			GearService gearService, ShoeService shoeService, ComponentRepository componentRepository, GearMapper gearMapper,
			EntityManager entityManager) {
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
		this.componentRepository = componentRepository;
		this.gearMapper = gearMapper;
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true)
	public void write(String athleteId, JsonGenerator generator) {
		generator.writeStartObject();
		generator.writeStringProperty("generated_at", Instant.now().toString());
		generator.writeStringProperty("athlete_id", athleteId);

		// Equipment and workouts come before activities (which reference bike/shoe/workout ids),
		// and activities come before races/scheduled_workouts (which reference activity ids) -
		// dependency order, so a forward-only streaming importer can resolve id references in one
		// pass without having to look ahead. See ImportReader for the reader side of this contract.
		writeEquipment(athleteId, generator);
		writeWorkouts(athleteId, generator);
		writeActivities(athleteId, generator);
		writeRaces(athleteId, generator);
		writeScheduledWorkouts(athleteId, generator);

		generator.writeEndObject();
	}

	private void writeActivities(String athleteId, JsonGenerator generator) {
		generator.writeArrayPropertyStart("activities");
		for (Activity activity : activityRepository.findByAthleteIdOrderByStartDate(athleteId)) {
			List<LapResponse> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId()).stream()
					.map(lapMapper::toResponse)
					.toList();
			// "high" resolution == no downsampling (StreamService.getStreams step=1 default) - a
			// full data export shouldn't quietly thin out the athlete's own recorded data.
			generator.writePOJO(new ActivityExportEntry(
					activityService.toResponse(activity), laps, streamService.getStreams(activity, null, "high")));
			entityManager.clear();
		}
		generator.writeEndArray();
	}

	private void writeRaces(String athleteId, JsonGenerator generator) {
		generator.writeArrayPropertyStart("races");
		for (var race : raceRepository.findByAthleteIdOrderByDateAsc(athleteId)) {
			generator.writePOJO(raceService.toResponse(race));
		}
		generator.writeEndArray();
	}

	private void writeWorkouts(String athleteId, JsonGenerator generator) {
		generator.writeArrayPropertyStart("workouts");
		for (Workout workout : workoutRepository.findByCreatedByIdOrderByIdDesc(athleteId)) {
			generator.writePOJO(new WorkoutDetailResponse(
					workoutMapper.toResponse(workout), workoutMapper.toStepTree(workout.getSteps())));
		}
		generator.writeEndArray();
	}

	private void writeScheduledWorkouts(String athleteId, JsonGenerator generator) {
		generator.writeArrayPropertyStart("scheduled_workouts");
		for (var scheduled : scheduledWorkoutRepository.findByAthleteIdOrderByDate(athleteId)) {
			generator.writePOJO(schedulingMapper.toResponse(scheduled));
		}
		generator.writeEndArray();
	}

	private void writeEquipment(String athleteId, JsonGenerator generator) {
		List<Bike> bikes = gearService.listBikes(athleteId);

		generator.writeObjectPropertyStart("equipment");

		List<BikeResponse> bikeResponses = bikes.stream().map(gearService::toBikeResponse).toList();
		generator.writePOJOProperty("bikes", bikeResponses);

		// Shoe/Component have no retired flag of their own to filter here; listShoes already
		// excludes retired shoes (findByAthleteIdAndRetiredFalseOrderByIdDesc) - matches the
		// "active gear only" scope for this export.
		List<ShoeResponse> shoeResponses = shoeService.listShoes(athleteId).stream().map(shoeService::toResponse).toList();
		generator.writePOJOProperty("shoes", shoeResponses);

		List<ComponentResponse> componentResponses = bikes.stream()
				.flatMap(bike -> componentRepository.findByBikeId(bike.getId()).stream())
				.map(gearMapper::toResponse)
				.toList();
		generator.writePOJOProperty("components", componentResponses);

		generator.writeEndObject();
	}
}
