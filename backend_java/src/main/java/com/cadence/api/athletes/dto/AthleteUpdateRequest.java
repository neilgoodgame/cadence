package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.LapSource;
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
		Boolean copyMatchedWorkoutTags,
		LapSource lapSource,
		Integer defaultShoeLimitKm,
		Integer thresholdWarningDays,
		Double decouplingViLimitBike,
		Double decouplingViLimitRun,
		Double decouplingIfLimit,
		Integer decouplingMinSteadyMinutes,
		Double decouplingWarmAirTemp,
		Double decouplingWarmSkinTemp,
		Double decouplingHotAirTemp,
		Double decouplingHotSkinTemp,
		// Appended at the end rather than grouped with the other decoupling fields above -
		// AthleteServiceIntegrationTest constructs this record positionally with many flat
		// all-null calls; appending avoids re-indexing every one of them (see backend_java's own
		// CLAUDE.md "Records used as DTOs" gotcha).
		Integer decouplingWarmupMinutes,
		Boolean decouplingUseWorkoutWarmup) {
}
