package com.cadence.api.mcp.dto;

import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.RunningPowerSource;

/**
 * Trimmed for {@code get_me} - drops {@code UserResponse}'s app-preference fields
 * (rename-matched-activity import settings, admin/email-verification flags) that mean nothing
 * to an AI assistant, keeping the training-context fields it actually needs.
 */
public record McpAthleteProfile(
		String id,
		String name,
		Integer age,
		Double weightKg,
		Integer ftp,
		FtpCalculationMethod ftpCalculationMethod,
		Integer criticalRunPower,
		RunningPowerSource runningPowerSource,
		String thresholdPace,
		Integer lthr,
		Integer maxHr,
		Integer restingHr,
		boolean isCoach) {
}
