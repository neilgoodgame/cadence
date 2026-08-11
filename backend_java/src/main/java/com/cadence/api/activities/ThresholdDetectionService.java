package com.cadence.api.activities;

import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.common.domain.Sport;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Flags {@code Activity.suggestedFtp}/{@code suggestedCriticalRunPower}/
 * {@code suggestedThresholdPace} when this activity's own best effort implies a higher
 * threshold than what's already on record ({@code activity.ftpSnapshot}/etc, set at ingest
 * from the athlete's profile - see {@link Activity}'s Javadoc). Computed directly from this
 * activity's raw streams rather than the persisted {@link BestEffort} table, which only gets
 * CYCLING_POWER/RUNNING_POWER rows once a threshold is <em>already</em> set (see
 * {@link BestEffortComputeService#computeForActivity}) - relying on it would silently miss
 * detecting a first-ever threshold. Never suggests a decrease.
 */
@Service
public class ThresholdDetectionService {

	// Bike FTP is conventionally estimated as 95% of best 20-minute power (the "20-minute test").
	private static final double FTP_TEST_MULTIPLIER = 0.95;
	private static final int FTP_TEST_WINDOW_SECONDS = 1200;
	// Running threshold (both criticalRunPower and thresholdPace) is conventionally estimated
	// directly from a sustained ~1-hour effort - no multiplier, unlike bike's 20-minute test.
	private static final int RUNNING_THRESHOLD_WINDOW_SECONDS = 3600;

	/** Mutates {@code activity} in place - caller is responsible for saving. */
	public void detect(Activity activity, List<Integer> powerSeries, List<Integer> tSeries, List<Double> distanceKmSeries) {
		if (activity.getSport() == Sport.BIKE) {
			Double best20Min = DurationCurveCalculator.bestAverage(powerSeries, FTP_TEST_WINDOW_SECONDS);
			if (best20Min != null) {
				int impliedFtp = (int) Math.round(FTP_TEST_MULTIPLIER * best20Min);
				int currentFtp = activity.getFtpSnapshot() != null ? activity.getFtpSnapshot() : 0;
				if (impliedFtp > currentFtp) {
					activity.setSuggestedFtp(impliedFtp);
				}
			}
		}
		else if (activity.getSport() == Sport.RUN) {
			Double best60MinPower = DurationCurveCalculator.bestAverage(powerSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
			if (best60MinPower != null) {
				int impliedCrp = (int) Math.round(best60MinPower);
				int currentCrp = activity.getCriticalRunPowerSnapshot() != null ? activity.getCriticalRunPowerSnapshot() : 0;
				if (impliedCrp > currentCrp) {
					activity.setSuggestedCriticalRunPower(impliedCrp);
				}
			}

			Double best60MinPace = PaceBestEffortCalculator.bestPaceSecondsPerKmOverDuration(
					tSeries, distanceKmSeries, RUNNING_THRESHOLD_WINDOW_SECONDS);
			if (best60MinPace != null) {
				Double currentPaceSeconds = mmssToSeconds(activity.getThresholdPaceSnapshot());
				// Pace is "lower is better" - a *faster* (smaller) implied pace is the improvement.
				if (currentPaceSeconds == null || best60MinPace < currentPaceSeconds) {
					activity.setSuggestedThresholdPace(secondsToMmss(best60MinPace));
				}
			}
		}
	}

	// A local copy, not a cross-package import of ZoneService's private helper - matches this
	// codebase's existing convention of small local duplicates over reaching into another
	// class's implementation detail (see e.g. ImportReader/ExportWriter's own Progress classes).
	private static Double mmssToSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(":");
		if (parts.length != 2) {
			return null;
		}
		try {
			int minutes = Integer.parseInt(parts[0]);
			int seconds = Integer.parseInt(parts[1]);
			return (double) (minutes * 60 + seconds);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String secondsToMmss(double seconds) {
		long total = Math.round(seconds);
		return (total / 60) + ":" + String.format("%02d", total % 60);
	}
}
