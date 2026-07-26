package com.cadence.api.workouts;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import com.cadence.api.workouts.dto.WorkoutCreateRequest;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import com.cadence.api.workouts.dto.WorkoutUpdateRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutService {

	private final WorkoutRepository workoutRepository;

	public WorkoutService(WorkoutRepository workoutRepository) {
		this.workoutRepository = workoutRepository;
	}

	public List<Workout> listWorkouts(String createdById) {
		return workoutRepository.findByCreatedByIdOrderByIdDesc(createdById);
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
		applySteps(workout, request.steps());
		return workoutRepository.save(workout);
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
		if (request.steps() != null) {
			applySteps(workout, request.steps());
		}
		return workoutRepository.save(workout);
	}

	@Transactional
	public void deleteWorkout(String id) {
		workoutRepository.deleteById(id);
	}

	private void requireBikeOrRun(Sport sport) {
		if (sport != Sport.BIKE && sport != Sport.RUN) {
			throw new ValidationException("sport must be bike or run.", "sport");
		}
	}

	private void applySteps(Workout workout, List<WorkoutStepDto> stepDtos) {
		workout.getSteps().clear();
		addSteps(workout, stepDtos, null);
		WorkoutCalculations.Result result = WorkoutCalculations.computeDurationAndTss(stepDtos);
		workout.setDuration(result.durationSeconds());
		workout.setTss(result.tss());
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
