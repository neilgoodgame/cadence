package com.cadence.api.workouts;

import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.athletes.ZoneService;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import com.cadence.api.workouts.dto.WorkoutUpdateRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutService {

	private static final Set<String> SORT_OPTIONS = Set.of("recent", "name", "duration", "tss", "used");

	private final WorkoutRepository workoutRepository;
	private final WorkoutFolderRepository workoutFolderRepository;
	private final WorkoutTagRepository workoutTagRepository;
	private final TagRepository tagRepository;
	private final WorkoutMapper workoutMapper;
	private final ZoneService zoneService;

	public WorkoutService(WorkoutRepository workoutRepository, WorkoutFolderRepository workoutFolderRepository,
			WorkoutTagRepository workoutTagRepository, TagRepository tagRepository, WorkoutMapper workoutMapper,
			ZoneService zoneService) {
		this.workoutRepository = workoutRepository;
		this.workoutFolderRepository = workoutFolderRepository;
		this.workoutTagRepository = workoutTagRepository;
		this.tagRepository = tagRepository;
		this.workoutMapper = workoutMapper;
		this.zoneService = zoneService;
	}

	/** tags comes from WorkoutTagRepository, not a mapped entity field - mirrors
	 * ActivityService.toResponse exactly. */
	public WorkoutResponse toResponse(Workout workout) {
		return workoutMapper.toResponse(workout, workoutTagRepository.findTagNamesByWorkoutId(workout.getId()));
	}

	public List<Workout> listWorkouts(
			String athleteId, String folderId, String tag, String sport, String search, String sort) {
		String effectiveSort = sort != null ? sort : "recent";
		if (!SORT_OPTIONS.contains(effectiveSort)) {
			throw new ValidationException("Must be one of recent, name, duration, tss, used.", "sort");
		}
		return workoutRepository.findFiltered(athleteId, folderId, tag, sport, search, effectiveSort);
	}

	public Workout getWorkout(String id) {
		return workoutRepository.findById(id).orElseThrow(() -> new NotFoundException("No such workout."));
	}

	public Workout getWorkoutWithSteps(String id) {
		return workoutRepository.findByIdWithSteps(id).orElseThrow(() -> new NotFoundException("No such workout."));
	}

	@Transactional
	public Workout createWorkout(User creator, WorkoutCreateRequest request) {
		requireBikeOrRun(request.sport());
		Workout workout = new Workout();
		workout.setCreatedBy(creator);
		workout.setName(request.name());
		workout.setSport(request.sport());
		workout.setFolder(resolveFolder(request.folderId(), creator.getId()));
		applySteps(workout, request.steps(), creator);
		workout = workoutRepository.save(workout);
		if (request.tags() != null) {
			for (String name : request.tags()) {
				attachTag(workout, creator, null, name);
			}
		}
		return workout;
	}

	/**
	 * Loads, mutates, and saves within a single transaction - replacing the step list touches
	 * a lazy collection, which a separately-loaded, already-detached entity can't do safely.
	 */
	@Transactional
	public Workout updateWorkout(String id, WorkoutUpdateRequest request) {
		Workout workout = getWorkoutWithSteps(id);
		if (request.name() != null) {
			workout.setName(request.name());
		}
		if (request.folderId() != null) {
			workout.setFolder(resolveFolder(request.folderId(), workout.getCreatedBy().getId()));
		}
		if (request.steps() != null) {
			applySteps(workout, request.steps(), workout.getCreatedBy());
		}
		return workoutRepository.save(workout);
	}

	@Transactional
	public void deleteWorkout(String id) {
		workoutRepository.deleteById(id);
	}

	/** Attach a tag to a workout by id or name - mirrors TagService.attachTag exactly (same
	 * shared Tag catalog), just targeting Workout/WorkoutTag instead of Activity/ActivityTag. */
	@Transactional
	public Tag attachTag(Workout workout, User athlete, String tagId, String name) {
		Tag tag;
		if (tagId != null && !tagId.isBlank()) {
			tag = tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("No such tag."));
		}
		else if (name != null && !name.isBlank()) {
			tag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), name).orElseGet(() -> {
				Tag created = new Tag();
				created.setAthlete(athlete);
				created.setName(name);
				created.setOrigin(TagOrigin.MANUAL);
				return tagRepository.save(created);
			});
		}
		else {
			throw new ValidationException("Provide either tag_id or name.", "tag_id");
		}

		if (!workoutTagRepository.existsByWorkoutIdAndTagId(workout.getId(), tag.getId())) {
			WorkoutTag link = new WorkoutTag();
			link.setWorkout(workout);
			link.setTag(tag);
			workoutTagRepository.save(link);
		}
		return tag;
	}

	/** Auto-applied tags can't be detached by users - same guard as TagService.detachTag. */
	@Transactional
	public void detachTag(String workoutId, String tagId) {
		WorkoutTag link = workoutTagRepository.findByWorkoutIdAndTagId(workoutId, tagId)
				.orElseThrow(() -> new NotFoundException("This tag is not attached to this workout."));
		if (link.getTag().getOrigin() == TagOrigin.AUTO) {
			throw new ForbiddenException("Auto-applied tags can't be removed.");
		}
		workoutTagRepository.delete(link);
	}

	private void requireBikeOrRun(Sport sport) {
		if (sport != Sport.BIKE && sport != Sport.RUN) {
			throw new ValidationException("sport must be bike or run.", "sport");
		}
	}

	/** {@code null} or blank clears the folder; otherwise resolves it, scoped to the owning athlete. */
	private WorkoutFolder resolveFolder(String folderId, String athleteId) {
		if (folderId == null || folderId.isBlank()) {
			return null;
		}
		WorkoutFolder folder = workoutFolderRepository
				.findById(folderId)
				.orElseThrow(() -> new NotFoundException("No such folder."));
		if (!folder.getCreatedBy().getId().equals(athleteId)) {
			throw new NotFoundException("No such folder.");
		}
		return folder;
	}

	private void applySteps(Workout workout, List<WorkoutStepDto> stepDtos, User athlete) {
		workout.getSteps().clear();
		addSteps(workout, stepDtos, null);
		Double thresholdPaceSecPerKm = zoneService.referenceFor(athlete, ZoneType.PACE);
		ZoneType powerZoneType = workout.getSport() == Sport.BIKE ? ZoneType.BIKE_POWER : ZoneType.RUN_POWER;
		Double powerReferenceWatts = zoneService.referenceFor(athlete, powerZoneType);
		List<WorkoutStepDto> normalized = WorkoutCalculations.normalizePowerUnits(stepDtos, powerReferenceWatts);
		WorkoutCalculations.Result result =
				WorkoutCalculations.computeDurationAndTss(normalized, workout.getSport(), thresholdPaceSecPerKm);
		workout.setDuration(result.durationSeconds());
		workout.setTss(result.tss());
		workout.setChartPreview(
				WorkoutCalculations.computeChartPreview(normalized, workout.getSport(), thresholdPaceSecPerKm));
	}

	/**
	 * Builds {@link WorkoutStep} entities bottom-up from the nested DTO tree, wiring
	 * {@code parentStep} via the in-memory object reference rather than a raw FK id, and
	 * adding every entity - leaves and groups, any depth - into the flat {@code workout.steps}
	 * collection. {@code cascade = CascadeType.ALL} on that collection persists the whole
	 * graph on save; Hibernate resolves FK insert ordering (parent row before its children)
	 * from the object graph at flush time.
	 */
	private void addSteps(Workout workout, List<WorkoutStepDto> stepDtos, WorkoutStep parent) {
		int order = 0;
		for (WorkoutStepDto dto : stepDtos) {
			WorkoutStep step = new WorkoutStep();
			step.setWorkout(workout);
			step.setParentStep(parent);
			step.setOrder(order++);
			step.setKind(dto.kind());
			step.setEndType(dto.endType());
			step.setDuration(dto.duration());
			step.setDistance(dto.distance());
			step.setTargetType(dto.targetType());
			step.setTargetLow(dto.targetLow());
			step.setTargetHigh(dto.targetHigh());
			step.setPowerUnit(dto.powerUnit() != null ? dto.powerUnit() : PowerUnit.PCT_FTP);
			step.setTarget2Type(dto.target2Type() != null ? dto.target2Type() : Target2Type.NONE);
			step.setTarget2Low(dto.target2Low());
			step.setTarget2High(dto.target2High());
			step.setRepeat(dto.repeat() != null ? dto.repeat() : 1);
			step.setNote(dto.note() != null ? dto.note() : "");
			workout.getSteps().add(step);
			if (dto.kind() == StepKind.REPEAT) {
				addSteps(workout, dto.children(), step);
			}
		}
	}
}
