package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.courses.CourseSections;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Activity splits - laps, 1km/5km fixed-distance splits, or (mode=elevation) one split per
 * section of the activity's own recorded elevation. No goal/target reference or delta here -
 * see RaceSplitsService for that, which reuses SplitsMath so the pace/GAP/grade math can't drift
 * between the two. */
@Service
public class ActivitySplitsService {

	public static final List<String> SPLITS_MODES = List.of("laps", "1", "5", "elevation");

	private final LapRepository lapRepository;
	private final RecordRepository recordRepository;
	private final ActivitySectionsService sectionsService;

	public ActivitySplitsService(LapRepository lapRepository, RecordRepository recordRepository,
			ActivitySectionsService sectionsService) {
		this.lapRepository = lapRepository;
		this.recordRepository = recordRepository;
		this.sectionsService = sectionsService;
	}

	private static double orZero(Double v) {
		return v != null ? v : 0.0;
	}

	private ActivitySplitsResponse.Halves halves(List<ActivitySplitsResponse.Split> splits) {
		if (splits.isEmpty()) return null;
		int mid = splits.size() / 2;
		int firstS = splits.subList(0, mid).stream().mapToInt(ActivitySplitsResponse.Split::timeS).sum();
		int secondS = splits.subList(mid, splits.size()).stream().mapToInt(ActivitySplitsResponse.Split::timeS).sum();
		return new ActivitySplitsResponse.Halves(firstS, secondS);
	}

	private ActivitySplitsResponse lapSplits(Activity activity) {
		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		// Laps don't store their own altitude, so this is the one split mode that needs the
		// activity's records just for grade - laps already have their own duration/avgHr/avgPower.
		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<ActivitySplitsResponse.Split> splits = new ArrayList<>();
		double cumulativeKm = 0.0;
		int cursor = 0;
		for (Lap lap : laps) {
			Double pace = SplitsMath.paceSPerKm(lap.getDuration(), lap.getDistanceKm());
			double startKm = cumulativeKm;
			double endKm = cumulativeKm + lap.getDistanceKm();
			while (cursor < rows.size() && orZero(rows.get(cursor).getDistanceKm()) < startKm) {
				cursor++;
			}
			Double startAltitude = null;
			Double endAltitude = null;
			int scan = cursor;
			while (scan < rows.size() && orZero(rows.get(scan).getDistanceKm()) <= endKm + 1e-6) {
				Double altitude = rows.get(scan).getAltitude();
				if (altitude != null) {
					if (startAltitude == null) startAltitude = altitude;
					endAltitude = altitude;
				}
				scan++;
			}
			Double avgGradePct = SplitsMath.avgGradePct(startAltitude, endAltitude, lap.getDistanceKm());
			Double gap = SplitsMath.gapPaceSPerKm(pace, avgGradePct);
			splits.add(new ActivitySplitsResponse.Split("Lap " + lap.getIndex(), SplitsMath.round2(startKm), SplitsMath.round2(endKm),
					lap.getDuration(), pace != null ? SplitsMath.round1(pace) : null, gap, lap.getAvgHr(), lap.getAvgPower(),
					null, null, null, avgGradePct));
			cumulativeKm = endKm;
		}
		return new ActivitySplitsResponse(laps.size() > 1, halves(splits), splits);
	}

	private ActivitySplitsResponse fixedDistanceSplits(Activity activity, double stepKm) {
		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<ActivitySplitsResponse.Split> splits = new ArrayList<>();
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
				List<Integer> powerSamples = new ArrayList<>();
				Double startAltitude = null;
				Double endAltitude = null;
				int scan = cursor;
				while (scan < rows.size() && orZero(rows.get(scan).getDistanceKm()) <= endKm + 1e-6) {
					Record row = rows.get(scan);
					if (startT == null) startT = row.getT();
					endT = row.getT();
					if (row.getHeartrate() != null) hrSamples.add(row.getHeartrate());
					if (row.getPower() != null) powerSamples.add(row.getPower());
					if (row.getAltitude() != null) {
						if (startAltitude == null) startAltitude = row.getAltitude();
						endAltitude = row.getAltitude();
					}
					scan++;
				}
				if (startT == null || endT == null) continue;
				int timeS = endT - startT;
				double distKm = endKm - startKm;
				Double pace = SplitsMath.paceSPerKm(timeS, distKm);
				String label = (i == nSplits - 1 && distKm < stepKm - 0.01) ? "Finish" : null;
				Integer avgHr = hrSamples.isEmpty() ? null : (int) Math.round(hrSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
				Integer avgPower = powerSamples.isEmpty() ? null : (int) Math.round(powerSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
				Double avgGradePct = SplitsMath.avgGradePct(startAltitude, endAltitude, distKm);
				Double gap = SplitsMath.gapPaceSPerKm(pace, avgGradePct);
				splits.add(new ActivitySplitsResponse.Split(label, SplitsMath.round2(startKm), SplitsMath.round2(endKm), timeS,
						pace != null ? SplitsMath.round1(pace) : null, gap, avgHr, avgPower,
						null, null, null, avgGradePct));
			}
		}

		return new ActivitySplitsResponse(false, halves(splits), splits);
	}

	private ActivitySplitsResponse elevationSplits(Activity activity) {
		ActivitySplitsResponse empty = new ActivitySplitsResponse(false, null, List.of());

		List<CourseSections.Section> sections = sectionsService.sectionsFor(activity);
		if (sections.isEmpty()) {
			return empty;
		}

		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		double totalKm = rows.isEmpty() ? 0.0 : orZero(rows.get(rows.size() - 1).getDistanceKm());
		if (rows.isEmpty() || totalKm <= 0) {
			return empty;
		}

		List<ActivitySplitsResponse.Split> splits = new ArrayList<>();
		int cursor = 0;
		for (CourseSections.Section section : sections) {
			double startKm = section.startKm();
			if (startKm >= totalKm) {
				break;
			}
			double endKm = Math.min(section.endKm(), totalKm);
			while (cursor < rows.size() && orZero(rows.get(cursor).getDistanceKm()) < startKm) {
				cursor++;
			}
			Integer startT = null;
			Integer endT = null;
			List<Integer> hrSamples = new ArrayList<>();
			List<Integer> powerSamples = new ArrayList<>();
			int scan = cursor;
			while (scan < rows.size() && orZero(rows.get(scan).getDistanceKm()) <= endKm + 1e-6) {
				Record row = rows.get(scan);
				if (startT == null) startT = row.getT();
				endT = row.getT();
				if (row.getHeartrate() != null) hrSamples.add(row.getHeartrate());
				if (row.getPower() != null) powerSamples.add(row.getPower());
				scan++;
			}
			if (startT == null || endT == null) continue;
			int timeS = endT - startT;
			double distKm = endKm - startKm;
			Double pace = SplitsMath.paceSPerKm(timeS, distKm);
			Integer avgHr = hrSamples.isEmpty() ? null : (int) Math.round(hrSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
			Integer avgPower = powerSamples.isEmpty() ? null : (int) Math.round(powerSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
			Double gap = SplitsMath.gapPaceSPerKm(pace, section.avgGradePct());
			splits.add(new ActivitySplitsResponse.Split(null, SplitsMath.round2(startKm), SplitsMath.round2(endKm), timeS,
					pace != null ? SplitsMath.round1(pace) : null, gap, avgHr, avgPower,
					section.type(), section.gainM(), section.lossM(), section.avgGradePct()));
		}

		return new ActivitySplitsResponse(false, halves(splits), splits);
	}

	// Writable (not readOnly) because mode=elevation can trigger the activity's first-read
	// elevation-series build (ActivitySectionsService.ensureElevation), which writes - a
	// readOnly transaction's JDBC connection would reject that write.
	@Transactional
	public ActivitySplitsResponse getSplits(Activity activity, String mode) {
		return switch (mode) {
			case "laps" -> lapSplits(activity);
			case "1" -> fixedDistanceSplits(activity, 1.0);
			case "5" -> fixedDistanceSplits(activity, 5.0);
			case "elevation" -> elevationSplits(activity);
			default -> throw new IllegalArgumentException("Unknown splits mode '" + mode + "'");
		};
	}
}
