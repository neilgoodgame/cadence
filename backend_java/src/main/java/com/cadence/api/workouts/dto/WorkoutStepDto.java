package com.cadence.api.workouts.dto;

import com.cadence.api.workouts.PowerUnit;
import com.cadence.api.workouts.StepEndType;
import com.cadence.api.workouts.StepKind;
import com.cadence.api.workouts.Target2Type;
import com.cadence.api.workouts.TargetType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A leaf step (warmup/block/rec/cool) or a {@code repeat} group. Groups carry
 * {@code repeat} and {@code children} instead of an end condition or target; a
 * group's children may themselves be nested {@code repeat} groups.
 */
public record WorkoutStepDto(
		@NotNull StepKind kind,
		StepEndType endType,
		Integer duration,
		Integer distance,
		TargetType targetType,
		Double targetLow,
		Double targetHigh,
		PowerUnit powerUnit,
		Target2Type target2Type,
		Double target2Low,
		Double target2High,
		Integer repeat,
		String note,
		List<@Valid WorkoutStepDto> children) {

	/** Returns a copy with targetLow/targetHigh replaced by their %FTP-equivalent and powerUnit
	 * flipped to PCT_FTP (used to normalize a "watts"-unit power leaf before TSS/duration/
	 * chart-preview math - see WorkoutCalculations.normalizePowerUnits). The powerUnit flip keeps
	 * this ephemeral copy self-consistent - its numbers and unit both now describe %FTP, so
	 * nothing downstream can misread an already-converted percentage as still being watts. Every
	 * other field is carried over unchanged. */
	public WorkoutStepDto withNormalizedPower(Double low, Double high) {
		return new WorkoutStepDto(kind, endType, duration, distance, targetType, low, high, PowerUnit.PCT_FTP,
				target2Type, target2Low, target2High, repeat, note, children);
	}

	@JsonIgnore
	@AssertTrue(message = "repeat groups must have at least one child and no leaf fields; "
			+ "leaf steps must have an end_type and target_type and no children.")
	public boolean isShapeConsistent() {
		if (kind == null) {
			return true;
		}
		if (kind == StepKind.REPEAT) {
			return endType == null && duration == null && distance == null && targetType == null
					&& children != null && !children.isEmpty();
		}
		return targetType != null && (children == null || children.isEmpty()) && isEndTypeConsistent();
	}

	private boolean isEndTypeConsistent() {
		if (endType == null) {
			return false;
		}
		if (endType == StepEndType.TIME) {
			return duration != null;
		}
		if (endType == StepEndType.DISTANCE) {
			return distance != null;
		}
		return true;
	}
}
