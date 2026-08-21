package com.cadence.api.users.dto;

import com.cadence.api.athletes.FtpCalculationMethod;

public record UserResponse(
		String id,
		String name,
		String email,
		boolean emailVerified,
		Integer age,
		Double weightKg,
		Integer ftp,
		Integer criticalRunPower,
		String thresholdPace,
		Integer lthr,
		Integer maxHr,
		Integer restingHr,
		int bestEffortTopN,
		int thresholdWindowDays,
		int thresholdSanityPct,
		int maxRunningPowerWatts,
		FtpCalculationMethod ftpCalculationMethod,
		boolean isCoach,
		boolean isAdmin,
		boolean renameMatchedActivities,
		boolean appendMatchDateToName,
		boolean copyMatchedWorkoutTags) {
}
