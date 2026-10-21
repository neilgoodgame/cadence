package com.cadence.api.athletes;

import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.races.RaceDistanceBuckets;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AthleteService {

	private static final Set<Integer> RACE_BLOCK_WEEKS_CHOICES = Set.of(12, 16, 20, 24);
	private static final Set<Integer> RACE_KEY_SESSION_CAP_CHOICES = Set.of(6, 8, 10, 12, 15, 20);
	private static final int MAX_RACE_BLOCK_WEEK_DEFAULT = 52;

	private final UserRepository userRepository;
	private final ZoneService zoneService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final ThresholdSuggestionService thresholdSuggestionService;

	public AthleteService(UserRepository userRepository, ZoneService zoneService, ThresholdHistoryService thresholdHistoryService,
			ThresholdSuggestionService thresholdSuggestionService) {
		this.userRepository = userRepository;
		this.zoneService = zoneService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.thresholdSuggestionService = thresholdSuggestionService;
	}

	/** Applies the patch and returns the zone types to report as recomputed - see {@link ZoneService#recomputedZoneTypes}. */
	@Transactional
	public List<ZoneType> updateProfile(User athlete, AthleteUpdateRequest request) {
		Set<String> changed = new HashSet<>();
		// Settings whose change can affect which Threshold suggestions are current (lead time
		// feeds detection directly) - tracked separately from `changed` (which only maps to zone
		// recomputation) so the suggestion cache invalidation below covers exactly these plus
		// ftp/criticalRunPower/thresholdPace, not every unrelated profile field too.
		boolean suggestionsAffected = false;
		if (request.name() != null) {
			athlete.setName(request.name());
			changed.add("name");
		}
		if (request.age() != null) {
			athlete.setAge(request.age());
			changed.add("age");
		}
		if (request.weightKg() != null) {
			athlete.setWeightKg(request.weightKg());
			changed.add("weightKg");
		}
		// A manually-entered threshold functions as an initial value (or a correction) just like
		// any other ledger entry - see ThresholdHistoryService.recordManualValue. The Preferences
		// form resubmits every field on every save regardless of whether it was edited, so
		// recordManualValue's own no-op-if-unchanged check is load-bearing here.
		if (request.ftp() != null) {
			athlete.setFtp(request.ftp());
			changed.add("ftp");
			thresholdHistoryService.recordManualValue(athlete, ThresholdField.FTP, request.ftp(), null);
			suggestionsAffected = true;
		}
		if (request.criticalRunPower() != null) {
			athlete.setCriticalRunPower(request.criticalRunPower());
			changed.add("criticalRunPower");
			thresholdHistoryService.recordManualValue(athlete, ThresholdField.CRITICAL_RUN_POWER, request.criticalRunPower(), null);
			suggestionsAffected = true;
		}
		if (request.thresholdPace() != null) {
			athlete.setThresholdPace(request.thresholdPace());
			changed.add("thresholdPace");
			thresholdHistoryService.recordManualValue(athlete, ThresholdField.THRESHOLD_PACE, null, request.thresholdPace());
			suggestionsAffected = true;
		}
		if (request.lthr() != null) {
			athlete.setLthr(request.lthr());
			changed.add("lthr");
		}
		if (request.maxHr() != null) {
			athlete.setMaxHr(request.maxHr());
			changed.add("maxHr");
		}
		if (request.restingHr() != null) {
			athlete.setRestingHr(request.restingHr());
			changed.add("restingHr");
		}
		if (request.bestEffortTopN() != null) {
			int n = request.bestEffortTopN();
			athlete.setBestEffortTopN(n == 0 ? 0 : Math.max(1, Math.min(50, n)));
			changed.add("bestEffortTopN");
		}
		if (request.maxRunningPowerWatts() != null) {
			// Bounded well below a real footpod glitch (~1500W+ observed) and well above even an
			// elite runner's sustained power, so an accidental extreme value here can't reopen the
			// hole RunningPowerSanitizer exists to close.
			athlete.setMaxRunningPowerWatts(Math.max(400, Math.min(2000, request.maxRunningPowerWatts())));
			changed.add("maxRunningPowerWatts");
		}
		if (request.ftpCalculationMethod() != null) {
			// Doesn't retroactively touch the existing ledger (same as maxRunningPowerWatts
			// above) - only affects candidates computed from here on. The athlete can already
			// rebuild history from scratch via the existing per-field Recompute tool if they
			// want past entries re-evaluated under the new method too.
			athlete.setFtpCalculationMethod(request.ftpCalculationMethod());
			changed.add("ftpCalculationMethod");
			suggestionsAffected = true;
		}
		if (request.thresholdWarningDays() != null) {
			athlete.setThresholdWarningDays(request.thresholdWarningDays());
			suggestionsAffected = true;
		}
		if (request.decouplingViLimitBike() != null) {
			athlete.setDecouplingViLimitBike(request.decouplingViLimitBike());
			changed.add("decouplingViLimitBike");
		}
		if (request.decouplingViLimitRun() != null) {
			athlete.setDecouplingViLimitRun(request.decouplingViLimitRun());
			changed.add("decouplingViLimitRun");
		}
		if (request.decouplingIfLimit() != null) {
			athlete.setDecouplingIfLimit(request.decouplingIfLimit());
			changed.add("decouplingIfLimit");
		}
		if (request.decouplingMinSteadyMinutes() != null) {
			athlete.setDecouplingMinSteadyMinutes(request.decouplingMinSteadyMinutes());
			changed.add("decouplingMinSteadyMinutes");
		}
		if (request.decouplingWarmupMinutes() != null) {
			athlete.setDecouplingWarmupMinutes(request.decouplingWarmupMinutes());
			changed.add("decouplingWarmupMinutes");
		}
		if (request.decouplingUseWorkoutWarmup() != null) {
			athlete.setDecouplingUseWorkoutWarmup(request.decouplingUseWorkoutWarmup());
			changed.add("decouplingUseWorkoutWarmup");
		}
		if (request.decouplingWarmAirTemp() != null) {
			athlete.setDecouplingWarmAirTemp(request.decouplingWarmAirTemp());
			changed.add("decouplingWarmAirTemp");
		}
		if (request.decouplingWarmSkinTemp() != null) {
			athlete.setDecouplingWarmSkinTemp(request.decouplingWarmSkinTemp());
			changed.add("decouplingWarmSkinTemp");
		}
		if (request.decouplingHotAirTemp() != null) {
			athlete.setDecouplingHotAirTemp(request.decouplingHotAirTemp());
			changed.add("decouplingHotAirTemp");
		}
		if (request.decouplingHotSkinTemp() != null) {
			athlete.setDecouplingHotSkinTemp(request.decouplingHotSkinTemp());
			changed.add("decouplingHotSkinTemp");
		}
		if (request.runningPowerSource() != null) {
			// Doesn't retroactively touch already-imported activities' stored Record.power
			// (see Activity.getPowerSource()'s Javadoc) - but every consumer of running power
			// (best efforts, TSS/derived stats, criticalRunPower threshold history) re-checks
			// each activity's own powerSource against this preference on every recompute, not
			// just at ingest, so switching this does correctly exclude already-imported
			// activities whose source no longer matches, without needing to re-upload them.
			athlete.setRunningPowerSource(request.runningPowerSource());
			changed.add("runningPowerSource");
		}
		if (request.renameMatchedActivities() != null) {
			athlete.setRenameMatchedActivities(request.renameMatchedActivities());
		}
		if (request.appendMatchDateToName() != null) {
			athlete.setAppendMatchDateToName(request.appendMatchDateToName());
		}
		if (request.copyMatchedWorkoutTags() != null) {
			athlete.setCopyMatchedWorkoutTags(request.copyMatchedWorkoutTags());
		}
		if (request.lapSource() != null) {
			athlete.setLapSource(request.lapSource());
		}
		if (request.defaultShoeLimitKm() != null) {
			athlete.setDefaultShoeLimitKm(request.defaultShoeLimitKm());
		}
		if (request.raceBlockWeeks() != null) {
			if (!RACE_BLOCK_WEEKS_CHOICES.contains(request.raceBlockWeeks())) {
				throw new ValidationException("Must be one of 12, 16, 20, 24.", "raceBlockWeeks");
			}
			athlete.setRaceBlockWeeks(request.raceBlockWeeks());
		}
		if (request.raceKeySessionCap() != null) {
			if (!RACE_KEY_SESSION_CAP_CHOICES.contains(request.raceKeySessionCap())) {
				throw new ValidationException("Must be one of 6, 8, 10, 12, 15, 20.", "raceKeySessionCap");
			}
			athlete.setRaceKeySessionCap(request.raceKeySessionCap());
		}
		if (request.courseSectionSettings() != null) {
			CourseSections.Settings merged = request.courseSectionSettings().mergeInto(athlete.getCourseSectionSettings());
			String error = merged.validationError();
			if (error != null) throw new ValidationException(error, "courseSectionSettings");
			athlete.setCourseSectionSettings(merged);
		}
		if (request.raceBlockWeekDefaults() != null) {
			Set<String> knownKeys = RaceDistanceBuckets.BUCKETS.stream().map(RaceDistanceBuckets.Bucket::key)
					.collect(java.util.stream.Collectors.toSet());
			for (var entry : request.raceBlockWeekDefaults().entrySet()) {
				if (!knownKeys.contains(entry.getKey())) {
					throw new ValidationException("Unknown race distance bucket \"" + entry.getKey() + "\".", "raceBlockWeekDefaults");
				}
				Integer weeks = entry.getValue();
				if (weeks == null || weeks < 1 || weeks > MAX_RACE_BLOCK_WEEK_DEFAULT) {
					throw new ValidationException(
							"\"" + entry.getKey() + "\" must be an integer between 1 and " + MAX_RACE_BLOCK_WEEK_DEFAULT + ".",
							"raceBlockWeekDefaults");
				}
			}
			Map<String, Integer> merged = new HashMap<>(athlete.getRaceBlockWeekDefaults());
			merged.putAll(request.raceBlockWeekDefaults());
			athlete.setRaceBlockWeekDefaults(merged);
		}
		userRepository.save(athlete);
		if (suggestionsAffected) {
			thresholdSuggestionService.invalidate(athlete.getId());
		}
		return zoneService.recomputedZoneTypes(athlete, changed);
	}
}
