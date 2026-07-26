package com.cadence.api.activities;

import com.cadence.api.activities.calc.DurationCurveCalculator;
import com.cadence.api.activities.calc.PaceBestEffortCalculator;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Core best-effort computation shared by the upload pipeline and the on-demand recompute endpoint.
 * Callers are responsible for transaction boundaries and for supplying the time-series data.
 */
@Service
public class BestEffortComputeService {

	private final BestEffortRepository bestEffortRepository;

	public BestEffortComputeService(BestEffortRepository bestEffortRepository) {
		this.bestEffortRepository = bestEffortRepository;
	}

	/** Compute all applicable kinds for one activity. */
	public void computeForActivity(Activity activity, User athlete,
			List<Integer> powerSeries, List<Integer> hrSeries, List<Double> distanceSeries) {
		int topN = athlete.getBestEffortTopN();
		boolean hasHr = hrSeries.stream().anyMatch(Objects::nonNull);

		if (activity.getSport() == Sport.BIKE) {
			if (athlete.getFtp() != null) {
				updatePowerBestEfforts(activity, athlete, BestEffortKind.CYCLING_POWER, powerSeries, topN);
			}
			if (hasHr) {
				updateHrBestEfforts(activity, athlete, BestEffortKind.CYCLING_HR, hrSeries, topN);
			}
		} else if (activity.getSport() == Sport.RUN) {
			if (athlete.getCriticalRunPower() != null && powerSeries.stream().anyMatch(Objects::nonNull)) {
				updatePowerBestEfforts(activity, athlete, BestEffortKind.RUNNING_POWER, powerSeries, topN);
			}
			updatePaceBestEfforts(activity, athlete, distanceSeries, topN);
			if (hasHr) {
				updateHrBestEfforts(activity, athlete, BestEffortKind.RUNNING_HR, hrSeries, topN);
			}
		}
	}

	/** Compute a single kind for one activity. */
	public void computeForActivity(Activity activity, User athlete, BestEffortKind kind,
			List<Integer> powerSeries, List<Integer> hrSeries, List<Double> distanceSeries) {
		int topN = athlete.getBestEffortTopN();
		switch (kind) {
			case CYCLING_POWER -> updatePowerBestEfforts(activity, athlete, kind, powerSeries, topN);
			case CYCLING_HR -> updateHrBestEfforts(activity, athlete, kind, hrSeries, topN);
			case RUNNING_POWER -> updatePowerBestEfforts(activity, athlete, kind, powerSeries, topN);
			case RUNNING_HR -> updateHrBestEfforts(activity, athlete, kind, hrSeries, topN);
			case RUNNING_PACE -> updatePaceBestEfforts(activity, athlete, distanceSeries, topN);
		}
	}

	private void updateHrBestEfforts(Activity activity, User athlete, BestEffortKind kind,
			List<Integer> hrSeries, int topN) {
		for (BestEffortWindows.PowerWindow window : BestEffortWindows.HR_BEST_EFFORT_WINDOWS) {
			if (window.seconds() > hrSeries.size()) continue;
			Double bestAvg = DurationCurveCalculator.bestAverage(hrSeries, window.seconds());
			if (bestAvg == null) continue;
			upsertEffort(activity, athlete, kind, window.label(), round1(bestAvg), "bpm");
			trimToTop(athlete.getId(), kind, window.label(), false, topN);
		}
	}

	private void updatePowerBestEfforts(Activity activity, User athlete, BestEffortKind kind,
			List<Integer> powerSeries, int topN) {
		for (BestEffortWindows.PowerWindow window : BestEffortWindows.POWER_BEST_EFFORT_WINDOWS) {
			if (window.seconds() > powerSeries.size()) continue;
			Double bestAvg = DurationCurveCalculator.bestAverage(powerSeries, window.seconds());
			if (bestAvg == null) continue;
			upsertEffort(activity, athlete, kind, window.label(), round1(bestAvg), "watts");
			trimToTop(athlete.getId(), kind, window.label(), false, topN);
		}
	}

	private void updatePaceBestEfforts(Activity activity, User athlete,
			List<Double> distanceSeries, int topN) {
		for (BestEffortWindows.PaceDistance distance : BestEffortWindows.PACE_BEST_EFFORT_DISTANCES_KM) {
			Double paceSecPerKm = PaceBestEffortCalculator.bestPaceSecondsPerKm(distanceSeries, distance.km());
			if (paceSecPerKm == null) continue;
			upsertEffort(activity, athlete, BestEffortKind.RUNNING_PACE, distance.label(), round1(paceSecPerKm), "sec_per_km");
			trimToTop(athlete.getId(), BestEffortKind.RUNNING_PACE, distance.label(), true, topN);
		}
	}

	private void upsertEffort(Activity activity, User athlete, BestEffortKind kind,
			String window, double value, String unit) {
		Optional<BestEffort> existing = bestEffortRepository.findByAthleteIdAndKindAndWindowAndActivityId(
				athlete.getId(), kind, window, activity.getId());
		BestEffort effort = existing.orElseGet(BestEffort::new);
		effort.setAthlete(athlete);
		effort.setKind(kind);
		effort.setWindow(window);
		effort.setValue(value);
		effort.setUnit(unit);
		effort.setDate(activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate());
		effort.setActivity(activity);
		bestEffortRepository.save(effort);
	}

	void trim(String athleteId, BestEffortKind kind, String window, boolean lowerIsBetter, int topN) {
		trimToTop(athleteId, kind, window, lowerIsBetter, topN);
	}

	private void trimToTop(String athleteId, BestEffortKind kind, String window,
			boolean lowerIsBetter, int topN) {
		if (topN == 0) return; // 0 = keep all
		List<BestEffort> ranked = lowerIsBetter
				? bestEffortRepository.findByAthleteIdAndKindAndWindowOrderByValueAsc(athleteId, kind, window)
				: bestEffortRepository.findByAthleteIdAndKindAndWindowOrderByValueDesc(athleteId, kind, window);
		if (ranked.size() > topN) {
			bestEffortRepository.deleteAll(ranked.subList(topN, ranked.size()));
		}
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
