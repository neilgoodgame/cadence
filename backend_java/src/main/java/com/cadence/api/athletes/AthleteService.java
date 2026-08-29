package com.cadence.api.athletes;

import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AthleteService {

	private final UserRepository userRepository;
	private final ZoneService zoneService;
	private final ThresholdHistoryService thresholdHistoryService;

	public AthleteService(UserRepository userRepository, ZoneService zoneService, ThresholdHistoryService thresholdHistoryService) {
		this.userRepository = userRepository;
		this.zoneService = zoneService;
		this.thresholdHistoryService = thresholdHistoryService;
	}

	/** Applies the patch and returns the zone types to report as recomputed - see {@link ZoneService#recomputedZoneTypes}. */
	@Transactional
	public List<ZoneType> updateProfile(User athlete, AthleteUpdateRequest request) {
		Set<String> changed = new HashSet<>();
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
		}
		if (request.criticalRunPower() != null) {
			athlete.setCriticalRunPower(request.criticalRunPower());
			changed.add("criticalRunPower");
			thresholdHistoryService.recordManualValue(athlete, ThresholdField.CRITICAL_RUN_POWER, request.criticalRunPower(), null);
		}
		if (request.thresholdPace() != null) {
			athlete.setThresholdPace(request.thresholdPace());
			changed.add("thresholdPace");
			thresholdHistoryService.recordManualValue(athlete, ThresholdField.THRESHOLD_PACE, null, request.thresholdPace());
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
		userRepository.save(athlete);
		return zoneService.recomputedZoneTypes(athlete, changed);
	}
}
