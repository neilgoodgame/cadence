package com.cadence.api.athletes.dto;

import com.cadence.api.athletes.FtpCalculationMethod;
import com.cadence.api.athletes.LapSource;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.courses.CourseSections;
import java.time.LocalDate;

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
		Boolean decouplingUseWorkoutWarmup,
		Integer raceBlockWeeks,
		Integer raceKeySessionCap,
		SectionSettingsUpdate courseSectionSettings,
		// Appended at the end for the same reason as decouplingWarmupMinutes above - avoids
		// re-indexing every existing positional AthleteUpdateRequest(...) test call site.
		java.util.Map<String, Integer> raceBlockWeekDefaults,
		// Issue #482: the frontend has always sent these, and openapi.yaml has always documented
		// them, but there was no field here to receive them, so Jackson silently dropped every
		// edit. Appended at the end for the same reason as above.
		Integer thresholdWindowDays,
		Integer thresholdSanityPct,
		// Issue #493: apply to whichever of ftp/criticalRunPower/thresholdPace is present in this
		// same request. effectiveFrom null means today (unchanged pre-#493 behavior).
		// rerateActivities null means true (matches this field's pre-#493 unconditional
		// behavior). preview null means false. Appended at the end for the same reason as above.
		LocalDate effectiveFrom,
		Boolean rerateActivities,
		Boolean preview) {

	/** Partial: any subset of CourseSections.Settings, merged into the athlete's current settings.
	 * Ranges are checked on the merged result, so a pair like flat/steep is checked together. */
	public record SectionSettingsUpdate(
			String algorithm, Double flatMaxPct, Double steepMinPct, Double fitToleranceM, Double minSectionM,
			Double keepMinRiseM, Double spikeWindowM, Double smoothingWindowM, Double resampleStepM) {

		public CourseSections.Settings mergeInto(CourseSections.Settings current) {
			return new CourseSections.Settings(
					algorithm != null ? algorithm : current.algorithm(),
					flatMaxPct != null ? flatMaxPct : current.flatMaxPct(),
					steepMinPct != null ? steepMinPct : current.steepMinPct(),
					fitToleranceM != null ? fitToleranceM : current.fitToleranceM(),
					minSectionM != null ? minSectionM : current.minSectionM(),
					keepMinRiseM != null ? keepMinRiseM : current.keepMinRiseM(),
					spikeWindowM != null ? spikeWindowM : current.spikeWindowM(),
					smoothingWindowM != null ? smoothingWindowM : current.smoothingWindowM(),
					resampleStepM != null ? resampleStepM : current.resampleStepM());
		}
	}
}
