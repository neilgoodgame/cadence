package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivitySplitsService;
import com.cadence.api.activities.Lap;
import com.cadence.api.activities.LapRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.SplitsMath;
import com.cadence.api.activities.dto.ActivitySplitsResponse;
import com.cadence.api.activities.dto.CustomSplitStepRequest;
import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.courses.CourseService;
import com.cadence.api.races.dto.SplitsResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Race splits - laps, 1km/5km fixed-distance splits, or (mode=elevation) one split per section
 * of the race's linked course - from a race's linked activity. */
@Service
public class RaceSplitsService {

	private final LapRepository lapRepository;
	private final RecordRepository recordRepository;
	private final com.cadence.api.activities.ActivityRepository activityRepository;
	private final CourseService courseService;
	private final ActivitySplitsService activitySplitsService;

	public RaceSplitsService(LapRepository lapRepository, RecordRepository recordRepository,
			com.cadence.api.activities.ActivityRepository activityRepository, CourseService courseService,
			ActivitySplitsService activitySplitsService) {
		this.lapRepository = lapRepository;
		this.recordRepository = recordRepository;
		this.activityRepository = activityRepository;
		this.courseService = courseService;
		this.activitySplitsService = activitySplitsService;
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

	private record RefPower(String ref, Integer power) {}

	/** Mirrors referencePace, but for Stryd-style power - preferred over GAP as the splits delta
	 * reference whenever the activity has power data, since power already accounts for grade
	 * mechanically rather than estimating it from a cost model. */
	private RefPower referencePower(Activity activity, Race race) {
		if (race.getTargetPower() != null) {
			return new RefPower("target", race.getTargetPower());
		}
		return new RefPower("average", activity.getAvgPower());
	}

	private Double pace(double timeS, double distanceKm) {
		return SplitsMath.paceSPerKm(timeS, distanceKm);
	}

	private Double avgGradePct(Double startAltitude, Double endAltitude, double distKm) {
		return SplitsMath.avgGradePct(startAltitude, endAltitude, distKm);
	}

	private Double deltaVsRef(Double paceSPerKm, Double refPaceSPerKm) {
		if (paceSPerKm == null || refPaceSPerKm == null) return null;
		return round1(paceSPerKm - refPaceSPerKm);
	}

	private Integer deltaPowerW(Integer avgPower, Integer refPowerW) {
		if (avgPower == null || refPowerW == null) return null;
		return avgPower - refPowerW;
	}

	/** Package-private (not private) so RaceSplitsServiceTest can exercise it directly, same as
	 * backend/races/splits.py's _gap_pace_s_per_km gets its own SimpleTestCase. Delegates to
	 * SplitsMath, shared with ActivitySplitsService so the two can't drift apart. */
	static Double gapPaceSPerKm(Double paceSPerKm, Double avgGradePct) {
		return SplitsMath.gapPaceSPerKm(paceSPerKm, avgGradePct);
	}

	private SplitsResponse.Halves halves(List<SplitsResponse.Split> splits) {
		if (splits.isEmpty()) return null;
		int mid = splits.size() / 2;
		int firstS = splits.subList(0, mid).stream().mapToInt(SplitsResponse.Split::timeS).sum();
		int secondS = splits.subList(mid, splits.size()).stream().mapToInt(SplitsResponse.Split::timeS).sum();
		return new SplitsResponse.Halves(firstS, secondS);
	}

	private SplitsResponse lapSplits(Activity activity, String ref, Double refPace, String refPower, Integer refPowerW) {
		List<Lap> laps = lapRepository.findByActivityIdOrderByIndex(activity.getId());
		// Laps don't store their own altitude, so this is the one split mode that needs the
		// activity's records just for grade - laps already have their own duration/avgHr/avgPower.
		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		List<SplitsResponse.Split> splits = new ArrayList<>();
		double cumulativeKm = 0.0;
		int cursor = 0;
		for (Lap lap : laps) {
			Double pace = pace(lap.getDuration(), lap.getDistanceKm());
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
			Double avgGradePct = avgGradePct(startAltitude, endAltitude, lap.getDistanceKm());
			Double gap = gapPaceSPerKm(pace, avgGradePct);
			splits.add(new SplitsResponse.Split("Lap " + lap.getIndex(), round2(startKm), round2(endKm),
					lap.getDuration(), pace != null ? round1(pace) : null, lap.getAvgHr(), deltaVsRef(gap != null ? gap : pace, refPace),
					null, null, null, avgGradePct, lap.getAvgPower(), gap, deltaPowerW(lap.getAvgPower(), refPowerW)));
			cumulativeKm = endKm;
		}
		return new SplitsResponse(laps.size() > 1, ref, refPace, halves(splits), splits, refPower, refPowerW);
	}

	private SplitsResponse fixedDistanceSplits(Activity activity, double stepKm, String ref, Double refPace, String refPower, Integer refPowerW) {
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
				Double pace = pace(timeS, distKm);
				String label = (i == nSplits - 1 && distKm < stepKm - 0.01) ? "Finish" : null;
				Integer avgHr = hrSamples.isEmpty() ? null : (int) Math.round(hrSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
				Integer avgPower = powerSamples.isEmpty() ? null : (int) Math.round(powerSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
				Double avgGradePct = avgGradePct(startAltitude, endAltitude, distKm);
				Double gap = gapPaceSPerKm(pace, avgGradePct);
				splits.add(new SplitsResponse.Split(label, round2(startKm), round2(endKm), timeS,
						pace != null ? round1(pace) : null, avgHr, deltaVsRef(gap != null ? gap : pace, refPace),
						null, null, null, avgGradePct, avgPower, gap, deltaPowerW(avgPower, refPowerW)));
			}
		}

		return new SplitsResponse(false, ref, refPace, halves(splits), splits, refPower, refPowerW);
	}

	private static double orZero(Double v) {
		return v != null ? v : 0.0;
	}

	private SplitsResponse elevationSplits(Race race, Activity activity, String ref, Double refPace, String refPower, Integer refPowerW) {
		SplitsResponse empty = new SplitsResponse(false, ref, refPace, null, List.of(), refPower, refPowerW);
		if (race.getCourse() == null) {
			return empty;
		}
		// Re-fetched fresh by id, not race.getCourse() directly - same cross-session
		// lazy-association gotcha as the activity re-fetch above / WeatherService.forecastFor.
		Course course = courseService.getCourse(race.getCourse().getId());
		course = courseService.ensureElevationSeries(course);
		List<CourseSections.Section> sections = courseService.sectionsFor(course, null);
		if (sections.isEmpty()) {
			return empty;
		}

		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		double totalKm = rows.isEmpty() ? 0.0 : orZero(rows.get(rows.size() - 1).getDistanceKm());
		if (rows.isEmpty() || totalKm <= 0) {
			return empty;
		}

		List<SplitsResponse.Split> splits = new ArrayList<>();
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
			Double pace = pace(timeS, distKm);
			Integer avgHr = hrSamples.isEmpty() ? null : (int) Math.round(hrSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
			Integer avgPower = powerSamples.isEmpty() ? null : (int) Math.round(powerSamples.stream().mapToInt(Integer::intValue).average().orElse(0));
			Double gap = gapPaceSPerKm(pace, section.avgGradePct());
			splits.add(new SplitsResponse.Split(null, round2(startKm), round2(endKm), timeS,
					pace != null ? round1(pace) : null, avgHr, deltaVsRef(gap != null ? gap : pace, refPace),
					section.type(), section.gainM(), section.lossM(), section.avgGradePct(), avgPower, gap, deltaPowerW(avgPower, refPowerW)));
		}

		return new SplitsResponse(false, ref, refPace, halves(splits), splits, refPower, refPowerW);
	}

	// Writable (not readOnly) because mode=elevation can trigger a course's first-read
	// elevation-series build (courseService.ensureElevationSeries), which writes - a readOnly
	// transaction's JDBC connection would reject that write.
	@Transactional
	public SplitsResponse getSplits(Race race, String mode) {
		if (race.getActivity() == null) {
			return new SplitsResponse(false, "average", null, null, List.of(), "average", null);
		}
		// Re-fetched fresh by id, not race.getActivity() directly - see RaceBlockService
		// .getRaceBlock's identical comment (same cross-session lazy-association gotcha).
		Activity activity = activityRepository.findById(race.getActivity().getId()).orElseThrow();

		Ref ref = referencePace(activity, race);
		RefPower refPower = referencePower(activity, race);

		return switch (mode) {
			case "laps" -> lapSplits(activity, ref.ref(), ref.pace(), refPower.ref(), refPower.power());
			case "1" -> fixedDistanceSplits(activity, 1.0, ref.ref(), ref.pace(), refPower.ref(), refPower.power());
			case "5" -> fixedDistanceSplits(activity, 5.0, ref.ref(), ref.pace(), refPower.ref(), refPower.power());
			case "elevation" -> elevationSplits(race, activity, ref.ref(), ref.pace(), refPower.ref(), refPower.power());
			default -> throw new IllegalArgumentException("Unknown splits mode '" + mode + "'");
		};
	}

	/** Custom splits (see ActivitySplitsService.customSplits) against race's own goal/target
	 * reference - layers ref/delta on top of the same step-walk every other mode shares, same as
	 * lapSplits/fixedDistanceSplits/elevationSplits do. */
	@Transactional
	public SplitsResponse customSplits(Race race, List<CustomSplitStepRequest> steps) {
		if (race.getActivity() == null) {
			return new SplitsResponse(false, "average", null, null, List.of(), "average", null);
		}
		Activity activity = activityRepository.findById(race.getActivity().getId()).orElseThrow();
		Ref ref = referencePace(activity, race);
		RefPower refPower = referencePower(activity, race);

		ActivitySplitsResponse base = activitySplitsService.customSplits(activity, steps); // throws on malformed steps

		List<SplitsResponse.Split> splits = base.splits().stream().map(s -> {
			Double paceForDelta = s.gapPaceSPerKm() != null ? s.gapPaceSPerKm() : s.paceSPerKm();
			return new SplitsResponse.Split(s.label(), s.startKm(), s.endKm(), s.timeS(), s.paceSPerKm(), s.avgHr(),
					deltaVsRef(paceForDelta, ref.pace()), s.sectionType(), s.gainM(), s.lossM(), s.avgGradePct(),
					s.avgPower(), s.gapPaceSPerKm(), deltaPowerW(s.avgPower(), refPower.power()));
		}).toList();

		return new SplitsResponse(false, ref.ref(), ref.pace(), halves(splits), splits, refPower.ref(), refPower.power());
	}

	private static double round1(double v) {
		return SplitsMath.round1(v);
	}

	private static double round2(double v) {
		return SplitsMath.round2(v);
	}
}
