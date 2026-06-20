package com.cadence.api.athletes.dto;

public record AthleteUpdateRequest(
		String name,
		Integer age,
		Integer ftp,
		Integer criticalRunPower,
		String thresholdPace,
		Integer lthr,
		Integer maxHr) {
}
