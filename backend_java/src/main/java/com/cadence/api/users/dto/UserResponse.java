package com.cadence.api.users.dto;

public record UserResponse(
		String id,
		String name,
		String email,
		Integer age,
		Double weightKg,
		Integer ftp,
		Integer criticalRunPower,
		String thresholdPace,
		Integer lthr,
		Integer maxHr,
		boolean isCoach) {
}
