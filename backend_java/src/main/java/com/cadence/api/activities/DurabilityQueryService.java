package com.cadence.api.activities;

import com.cadence.api.athletes.dto.DurabilityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles GET /v1/athletes/{id}/durability - aerobic decoupling sessions (trend + rolling
 * average) and durability (best power once tired) for the Best Efforts screen's Durability
 * view. Mirrors the Python backend's AthleteDurabilityView exactly, including the wire
 * vocabulary translation (this codebase's Activity.sport=BIKE is "ride" on the wire throughout -
 * query param, JSON sport field, the durability section's own top-level key - matching the
 * design spec; translated at this edge only, never renamed elsewhere).
 */
@Service
public class DurabilityQueryService {

	private final ActivityRepository activityRepository;
	private final ActivityDurabilityRepository durabilityRepository;

	public DurabilityQueryService(ActivityRepository activityRepository, ActivityDurabilityRepository durabilityRepository) {
		this.activityRepository = activityRepository;
		this.durabilityRepository = durabilityRepository;
	}

	private record Point(
			String activityId, String name, LocalDate date, Sport sport, Double decouplingPct, Double ef,
			Double efFirst, Double efSecond, Integer steadySeconds, boolean hot, Double avgTemp, Double avgCore) {}

	// Open-in-view is disabled (application.yml) - section() below reads best.getActivity().
	// getName(), a non-id property that genuinely triggers lazy initialization (unlike
	// .getId(), which Hibernate's proxy already knows without a DB hit), so the whole
	// assembly needs a live session for as long as that access happens.
	@Transactional(readOnly = true)
	public DurabilityResponse get(User athlete, String sportParam, String period) {
		List<Sport> sports = switch (sportParam) {
			case "ride" -> List.of(Sport.BIKE);
			case "run" -> List.of(Sport.RUN);
			default -> List.of(Sport.BIKE, Sport.RUN);
		};
		LocalDate cutoff = switch (period) {
			case "4w" -> LocalDate.now().minusDays(28);
			case "16w" -> LocalDate.now().minusDays(112);
			case "1y" -> LocalDate.now().minusDays(365);
			default -> null;
		};
		// Instant.EPOCH, not Instant.MIN, as the "no cutoff" sentinel - MIN's absurdly distant
		// value overflows a long once the JDBC driver converts it to epoch millis. No real
		// activity predates 1970 either way.
		java.time.Instant sinceInstant = cutoff != null ? cutoff.atStartOfDay(ZoneOffset.UTC).toInstant() : java.time.Instant.EPOCH;

		List<Point> points = new ArrayList<>();
		for (Sport sport : sports) {
			List<Activity> activities =
					activityRepository.findByAthleteIdAndSportAndParentActivityIsNullAndDecouplingQualifiedTrueAndStartDateGreaterThanEqualOrderByStartDateAsc(
							athlete.getId(), sport, sinceInstant);
			for (Activity a : activities) {
				points.add(new Point(
						a.getId(), a.getName(), a.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), sport,
						a.getDecouplingPct(), wholeWindowEf(a), a.getEfFirst(), a.getEfSecond(), a.getSteadySeconds(),
						a.isDecouplingHot(), a.getDecouplingAvgTemp(), a.getDecouplingAvgCore()));
			}
		}
		points.sort((a, b) -> a.date().compareTo(b.date()));

		List<DurabilityResponse.Session> sessions = points.stream()
				.map(p -> new DurabilityResponse.Session(
						p.activityId(), p.name(), p.date(), wireSport(p.sport()), p.decouplingPct(), p.ef(),
						p.efFirst(), p.efSecond(), p.steadySeconds(), p.hot(), p.avgTemp(), p.avgCore()))
				.toList();

		List<DurabilityResponse.Rolling.Point> ridePoints =
				sports.contains(Sport.BIKE) ? rollingSeries(points.stream().filter(p -> p.sport() == Sport.BIKE).toList()) : null;
		List<DurabilityResponse.Rolling.Point> runPoints =
				sports.contains(Sport.RUN) ? rollingSeries(points.stream().filter(p -> p.sport() == Sport.RUN).toList()) : null;
		DurabilityResponse.Rolling rolling = new DurabilityResponse.Rolling(ridePoints, runPoints);

		LocalDate today = LocalDate.now();
		List<Point> last28 = points.stream().filter(p -> java.time.temporal.ChronoUnit.DAYS.between(p.date(), today) <= 28).toList();
		List<Point> prev28 = points.stream()
				.filter(p -> {
					long days = java.time.temporal.ChronoUnit.DAYS.between(p.date(), today);
					return days > 28 && days <= 56;
				})
				.toList();
		List<Point> last28Cool = last28.stream().filter(p -> !p.hot()).toList();

		DurabilityResponse.Summary summary = new DurabilityResponse.Summary(
				points.size(),
				avg1(last28.stream().map(Point::decouplingPct).toList()),
				avg1(prev28.stream().map(Point::decouplingPct).toList()),
				avg1(last28Cool.stream().map(Point::decouplingPct).toList()),
				avg3(last28.stream().map(Point::ef).toList()),
				avg3(prev28.stream().map(Point::ef).toList()));

		DurabilityResponse.Sections.SportSection rideSection =
				sports.contains(Sport.BIKE) ? section(athlete, Sport.BIKE, cutoff) : null;
		DurabilityResponse.Sections.SportSection runSection =
				sports.contains(Sport.RUN) ? section(athlete, Sport.RUN, cutoff) : null;

		return new DurabilityResponse(sessions, rolling, summary, new DurabilityResponse.Sections(rideSection, runSection));
	}

	/** Whole-steady-window EF (avg power / avg HR), derived from the two stored halves rather
	 * than a separately-stored field - a sample-count-weighted combination of two partitions'
	 * own averages is exactly the whole-window average, not an approximation. */
	private Double wholeWindowEf(Activity activity) {
		List<Map<String, Object>> halves = activity.getDecouplingHalves();
		if (halves == null || halves.size() != 2) {
			return null;
		}
		double weightedPower = 0;
		double weightedHr = 0;
		double totalWeight = 0;
		for (Map<String, Object> half : halves) {
			Number power = (Number) half.get("power");
			Number hr = (Number) half.get("hr");
			Number startS = (Number) half.get("start_s");
			Number endS = (Number) half.get("end_s");
			if (power == null || hr == null || startS == null || endS == null) {
				return null;
			}
			double weight = endS.doubleValue() - startS.doubleValue() + 1;
			weightedPower += power.doubleValue() * weight;
			weightedHr += hr.doubleValue() * weight;
			totalWeight += weight;
		}
		if (totalWeight <= 0 || weightedHr <= 0) {
			return null;
		}
		return round3((weightedPower / totalWeight) / (weightedHr / totalWeight));
	}

	/** One point per session date - value = mean of that sport's sessions in (date - 28d,
	 * date]. O(n^2) in the number of qualified sessions, fine for a per-request computation. */
	private List<DurabilityResponse.Rolling.Point> rollingSeries(List<Point> sportPoints) {
		List<DurabilityResponse.Rolling.Point> rolling = new ArrayList<>();
		for (Point point : sportPoints) {
			LocalDate windowStart = point.date().minusDays(28);
			List<Point> window = sportPoints.stream()
					.filter(p -> p.date().isAfter(windowStart) && !p.date().isAfter(point.date()))
					.toList();
			rolling.add(new DurabilityResponse.Rolling.Point(point.date(), avg1(window.stream().map(Point::decouplingPct).toList()), avg3(window.stream().map(Point::ef).toList())));
		}
		return rolling;
	}

	private DurabilityResponse.Sections.SportSection section(User athlete, Sport sport, LocalDate cutoff) {
		String basis = sport == Sport.BIKE ? "kj" : "minutes";
		List<Integer> thresholds = sport == Sport.BIKE ? List.of(0, 1000, 2000, 3000) : List.of(0, 60, 90);
		List<Integer> windows = List.of(300, 1200, 3600);

		List<ActivityDurability> rows = cutoff != null
				? durabilityRepository.findByAthleteIdAndSportAndActivityDateGreaterThanEqual(athlete.getId(), sport, cutoff)
				: durabilityRepository.findByAthleteIdAndSport(athlete.getId(), sport);

		Map<List<Integer>, ActivityDurability> bestByCell = new LinkedHashMap<>();
		for (ActivityDurability row : rows) {
			List<Integer> key = List.of(row.getThreshold(), row.getWindowS());
			ActivityDurability current = bestByCell.get(key);
			if (current == null || row.getPower() > current.getPower()) {
				bestByCell.put(key, row);
			}
		}

		List<DurabilityResponse.Sections.Cell> cells = new ArrayList<>();
		for (int threshold : thresholds) {
			for (int windowS : windows) {
				ActivityDurability best = bestByCell.get(List.of(threshold, windowS));
				if (best == null) {
					continue;
				}
				Integer pct;
				if (threshold == 0) {
					pct = 100;
				}
				else {
					ActivityDurability fresh = bestByCell.get(List.of(0, windowS));
					pct = fresh != null && fresh.getPower() > 0 ? Math.round(best.getPower() / (float) fresh.getPower() * 100) : null;
				}
				cells.add(new DurabilityResponse.Sections.Cell(
						windowS, threshold, best.getPower(), pct, best.getActivity().getId(),
						best.getActivity().getName(), best.getActivityDate()));
			}
		}
		return new DurabilityResponse.Sections.SportSection(basis, thresholds, windows, cells);
	}

	private String wireSport(Sport sport) {
		return sport == Sport.BIKE ? "ride" : "run";
	}

	private Double avg1(List<Double> values) {
		List<Double> present = values.stream().filter(java.util.Objects::nonNull).toList();
		if (present.isEmpty()) {
			return null;
		}
		return round1(present.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
	}

	private Double avg3(List<Double> values) {
		List<Double> present = values.stream().filter(java.util.Objects::nonNull).toList();
		if (present.isEmpty()) {
			return null;
		}
		return round3(present.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
	}

	private double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private double round3(double v) {
		return Math.round(v * 1000) / 1000.0;
	}
}
