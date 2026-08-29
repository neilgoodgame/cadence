package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.RunningPowerSource;

public record AthleteUpdateRequest(
		String name,
		Integer age,
		Double weightKg,
		Integer ftp,
		Integer criticalRunPower,
		String thresholdPace,
		Integer lthr,
		Integer maxHr,
		Integer restingHr,
		Integer bestEffortTopN,
		Integer maxRunningPowerWatts,
		FtpCalculationMethod ftpCalculationMethod,
		RunningPowerSource runningPowerSource,
		Boolean renameMatchedActivities,
		Boolean appendMatchDateToName,
		Boolean copyMatchedWorkoutTags) {
}
