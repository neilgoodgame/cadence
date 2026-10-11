package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.races.dto.SplitsResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Race splits - laps, 1km and 5km fixed-distance splits from a race's linked activity. */
@Service
public class RaceSplitsService {

	private final LapRepository lapRepository;
	private final RecordRepository recordRepository;
	private final com.cadence.api.activities.ActivityRepository activityRepository;

	public RaceSplitsService(LapRepository lapRepository, RecordRepository recordRepository,
			com.cadence.api.activities.ActivityRepository activityRepository) {
		this.lapRepository = lapRepository;
		this.recordRepository = recordRepository;
		this.activityRepository = activityRepository;
	}

	private record Ref(String ref, Double pace) {}

	private Ref referencePace(Activity activity, Race race) {
		if (race.getGoalTime() != null && race.getDistanceKm() != null) {
			return new Ref("goal", race.getGoalTime() / race.getDistanceKm());
		}
		if (activity.getDistanceKm() > 0) {
			return new Ref("average", activity.getMovingTime() / activity.getDistanceKm());
		}
		return new Ref("average", null);
	}

	private Double pace(double timeS, double distanceKm) {
		return distanceKm > 0 ? timeS / distanceKm : null;
	}

	private Double deltaVsRef(Double paceSPerKm, Double refPaceSPerKm) {
		if (paceSPerKm == null || refPaceSPerKm == null) return null;
		return round1(paceSPerKm - refPaceSPerKm);
	}

	private SplitsResponse.Halves halves(List<SplitsResponse.Split> splits) {
		if (splits.isEmpty()) return null;
		int mid = splits.size() / 2;
		int firstS = splits.subList(0, mid).stream().mapToInt(SplitsResponse.Split::timeS).sum();
		int secondS = splits.subList(mid, splits.size()).stream().mapToInt(SplitsResponse.Split::timeS).sum();
		return new SplitsResponse.Halves(firstS, secondS);
	}

	private SplitsResponse lapSplits(Activity activity, String ref, Double refPace) {
		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		List<SplitsResponse.Split> splits = new ArrayList<>();
		double cumulativeKm = 0.0;
		for (Lap lap : laps) {
			Double pace = pace(lap.getDuration(), lap.getDistanceKm());
			splits.add(new SplitsResponse.Split("Lap " + lap.getIndex(), round2(cumulativeKm), round2(cumulativeKm + lap.getDistanceKm()),
					lap.getDuration(), pace != null ? round1(pace) : null, lap.getAvgHr(), deltaVsRef(pace, refPace)));
			cumulativeKm += lap.getDistanceKm();
		}
		return new SplitsResponse(laps.size() > 1, ref, refPace, halves(splits), splits);
	}

	private SplitsResponse fixedDistanceSplits(Activity activity, double stepKm, String ref, Double refPace) {
		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<SplitsResponse.Split> splits = new ArrayList<>();
		double totalKm = rows.isEmpty() ? 0.0 : orZero(rows.get(rows.size() - 1).getDistanceKm());

		if (!rows.isEmpty() && totalKm > 0) {
			int nSplits = (int) (totalKm / stepKm) + (totalKm % stepKm > 0.01 ? 1 : 0);
			int cursor = 0;
			for (int i = 0; i < nSplits; i++) {
				double startKm = i * stepKm;
				double endKm = Math.min(startKm + stepKm, totalKm);
				while (cursor < rows.size() && orZero(rows.get(cursor).getDistanceKm()) < startKm) {
					cursor++;
				}
				Integer startT = null;
				Integer endT = null;
				List<Integer> hrSamples = new ArrayList<>();
				int scan = cursor;
				while (scan < rows.size() && orZero(rows.get(scan).getDistanceKm()) <= endKm + 1e-6) {
					if (startT == null) startT = rows.get(scan).getT();
					endT = rows.get(scan).getT();
					if (rows.get(scan).getHeartrate() != null) hrSamples.add(rows.get(scan).getHeartrate());
					scan++;
				}
				if (startT == null || endT == null) continue;
				int timeS = endT - startT;
				double distKm = endKm - startKm;
				Double pace = pace(timeS, distKm);
				String label = (i == nSplits - 1 && distKm < stepKm - 0.01) ? "Finish" : null;
				Integer avgHr = hrSamples.isEmpty() ? null : (int) Math.round(hrSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
				splits.add(new SplitsResponse.Split(label, round2(startKm), round2(endKm), timeS,
						pace != null ? round1(pace) : null, avgHr, deltaVsRef(pace, refPace)));
			}
		}

		return new SplitsResponse(false, ref, refPace, halves(splits), splits);
	}

	private static double orZero(Double v) {
		return v != null ? v : 0.0;
	}

	@Transactional(readOnly = true)
	public SplitsResponse getSplits(Race race, String mode) {
		if (race.getActivity() == null) {
			return new SplitsResponse(false, "average", null, null, List.of());
		}
		// Re-fetched fresh by id, not race.getActivity() directly - see RaceBlockService
		// .getRaceBlock's identical comment (same cross-session lazy-association gotcha).
		Activity activity = activityRepository.findById(race.getActivity().getId()).orElseThrow();

		Ref ref = referencePace(activity, race);

		return switch (mode) {
			case "laps" -> lapSplits(activity, ref.ref(), ref.pace());
			case "1" -> fixedDistanceSplits(activity, 1.0, ref.ref(), ref.pace());
			case "5" -> fixedDistanceSplits(activity, 5.0, ref.ref(), ref.pace());
			default -> throw new IllegalArgumentException("Unknown splits mode '" + mode + "'");
		};
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
