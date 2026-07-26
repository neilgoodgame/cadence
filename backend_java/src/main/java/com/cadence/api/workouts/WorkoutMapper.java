package com.cadence.api.workouts;

import com.cadence.api.workouts.dto.WorkoutResponse;
import com.cadence.api.workouts.dto.WorkoutStepDto;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WorkoutMapper {

	WorkoutResponse toResponse(Workout workout);

	@Mapping(target = "children", ignore = true)
	WorkoutStepDto toDto(WorkoutStep step);

	/**
	 * Builds the nested {@code children} tree from a flat, self-referencing step
	 * list (grouped by {@code parentStep}) - MapStruct can't derive this
	 * automatically from a self-referential entity association.
	 */
	default List<WorkoutStepDto> toStepTree(List<WorkoutStep> steps) {
		Map<Long, List<WorkoutStep>> byParent = steps.stream()
				.collect(Collectors.groupingBy(s -> s.getParentStep() != null ? s.getParentStep().getId() : -1L));
		return buildLevel(byParent, -1L);
	}

	private List<WorkoutStepDto> buildLevel(Map<Long, List<WorkoutStep>> byParent, Long parentId) {
		return byParent.getOrDefault(parentId, List.of()).stream()
				.sorted(Comparator.comparingInt(WorkoutStep::getOrder))
				.map(s -> {
					WorkoutStepDto dto = toDto(s);
					List<WorkoutStepDto> children = s.getKind() == StepKind.REPEAT
							? buildLevel(byParent, s.getId())
							: List.of();
					return new WorkoutStepDto(dto.kind(), dto.endType(), dto.duration(), dto.distance(),
							dto.targetType(), dto.targetLow(), dto.targetHigh(), dto.target2Type(),
							dto.target2Low(), dto.target2High(), dto.repeat(), dto.note(), children);
				})
				.toList();
	}
}
