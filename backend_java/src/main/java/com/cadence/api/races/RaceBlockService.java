package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.BestEffort;
import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.BestEffortRepository;
import com.cadence.api.activities.DurabilityQueryService;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.athletes.BestEffortController;
import com.cadence.api.athletes.FitnessPoint;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.races.dto.RaceBlockResponse;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.users.User;
import com.cadence.api.workouts.Workout;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Race training-block rules - see README §3.2 in design_handoff_races. Mirrors the Python
 * backend's races/blocks.py function-for-function, including its two deliberate
 * simplifications versus the design doc (see that module's own docstring):
 * - Key-session "quality" candidates check IF >= 0.85 for any sport, but the "20 min at goal
 * pace" alternative is only evaluated for run races (no per-race goal-power field to compare a
 * ride against).
 * - "Planned" key-session candidates use a duration/type heuristic since Workout has no
 * explicit long/quality flag.
 */
@Service
public class RaceBlockService {

	private static final int CTL_DAYS = 42;
	private static final int ATL_DAYS = 7;
	private static final double RIEGEL_EXPONENT = 1.06;
	private static final double COMPARISON_DISTANCE_TOLERANCE = 0.02;
	private static final int LONG_WORKOUT_DURATION_S = 90 * 60;
	private static final List<String> QUALITY_TYPE_KEYWORDS =
			List.of("threshold", "tempo", "vo2", "race pace", "marathon pace", "mp");
	private static final double QUALITY_INTENSITY_FACTOR = 0.85;
	private static final int GOAL_PACE_WINDOW_S = 20 * 60;

	private static final Map<String, Integer> REASON_RANK =
			Map.of("race", 0, "tagged", 1, "long", 2, "quality", 3, "best_effort", 4, "planned", 5);

	private final RaceRepository raceRepository;
	private final ActivityRepository activityRepository;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;
	private final BestEffortRepository bestEffortRepository;
	private final RecordRepository recordRepository;
	private final DurabilityQueryService durabilityQueryService;

	private final com.cadence.api.users.UserRepository userRepository;

	public RaceBlockService(RaceRepository raceRepository, ActivityRepository activityRepository,
			ScheduledWorkoutRepository scheduledWorkoutRepository, TagRepository tagRepository,
			ActivityTagRepository activityTagRepository, BestEffortRepository bestEffortRepository,
			RecordRepository recordRepository, DurabilityQueryService durabilityQueryService,
			com.cadence.api.users.UserRepository userRepository) {
		this.raceRepository = raceRepository;
		this.activityRepository = activityRepository;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
		this.bestEffortRepository = bestEffortRepository;
		this.recordRepository = recordRepository;
		this.durabilityQueryService = durabilityQueryService;
		this.userRepository = userRepository;
	}

	// --- week arithmetic ---------------------------------------------------------------

	private static LocalDate weekStart(LocalDate d) {
		return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	private static String tsbBand(double tsb) {
		if (tsb > 25) return "very_fresh";
		if (tsb >= 5) return "fresh";
		if (tsb >= -10) return "neutral";
		return "fatigued";
	}

	// --- block range / phases ------------------------------------------------------------

	public record BlockRange(LocalDate startWeek, LocalDate endWeek, int weeks, String truncatedByRaceId) {}

	public BlockRange aRaceBlockRange(Race race, int blockWeeks) {
		LocalDate raceWeek = weekStart(race.getDate());
		LocalDate idealStart = raceWeek.minusWeeks(blockWeeks - 1L);
		Race prevA = raceRepository
				.findFirstByAthleteIdAndPriorityAndDateLessThanOrderByDateDesc(race.getAthlete().getId(), RacePriority.A, race.getDate())
				.orElse(null);
		LocalDate startWeek = prevA != null ? maxDate(idealStart, weekStart(prevA.getDate()).plusWeeks(1)) : idealStart;
		int weeks = (int) (java.time.temporal.ChronoUnit.DAYS.between(startWeek, raceWeek) / 7) + 1;
		boolean truncated = weeks < blockWeeks;
		return new BlockRange(startWeek, raceWeek, weeks, truncated && prevA != null ? prevA.getId() : null);
	}

	public Race findParentARace(Race race, int blockWeeks) {
		LocalDate bWeek = weekStart(race.getDate());
		List<Race> candidates = raceRepository
				.findByAthleteIdAndPriorityAndDateGreaterThanEqualOrderByDateAsc(race.getAthlete().getId(), RacePriority.A, race.getDate());
		for (Race a : candidates) {
			BlockRange block = aRaceBlockRange(a, blockWeeks);
			if (!bWeek.isBefore(block.startWeek()) && !bWeek.isAfter(block.endWeek())) {
				return a;
			}
		}
		return null;
	}

	private List<RaceBlockResponse.Phase> phases(int nWeeks) {
		int base = Math.round(0.375f * nWeeks);
		int build = Math.round(0.375f * nWeeks);
		int peak = Math.max(1, Math.round(0.125f * nWeeks));
		int taper = nWeeks - base - build - peak;
		List<RaceBlockResponse.Phase> result = new ArrayList<>();
		int weekNum = 1;
		for (var entry : List.of(Map.entry("Base", base), Map.entry("Build", build), Map.entry("Peak", peak), Map.entry("Taper", taper))) {
			int length = entry.getValue();
			if (length <= 0) continue;
			result.add(new RaceBlockResponse.Phase(entry.getKey(), weekNum, weekNum + length - 1));
			weekNum += length;
		}
		return result;
	}

	private static LocalDate maxDate(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	// --- fitness series, including a forward planned-TSS projection --------------------

	private Map<LocalDate, Integer> dailyTss(String athleteId, LocalDate start, LocalDate to) {
		if (start.isAfter(to)) return Map.of();
		Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant endInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		List<Object[]> rows = activityRepository.findStartDatesAndTssInRange(athleteId, startInstant, endInstant);
		Map<LocalDate, Integer> result = new HashMap<>();
		for (Object[] row : rows) {
			Instant startDate = (Instant) row[0];
			Integer tss = (Integer) row[1];
			LocalDate day = startDate.atZone(ZoneOffset.UTC).toLocalDate();
			result.merge(day, tss != null ? tss : 0, Integer::sum);
		}
		return result;
	}

	private Map<LocalDate, Integer> plannedDailyTss(String athleteId, LocalDate start, LocalDate end) {
		if (start.isAfter(end)) return Map.of();
		Map<LocalDate, Integer> totals = new HashMap<>();
		for (ScheduledWorkout sw : scheduledWorkoutRepository.findByAthleteIdAndDateBetweenOrderByDate(athleteId, start, end)) {
			totals.merge(sw.getDate(), sw.getWorkout().getTss(), Integer::sum);
		}
		return totals;
	}

	/** Like FitnessService.computeFitnessSeries, but days after `today` use planned TSS from
	 * ScheduledWorkout instead of 0 - the training-block chart's projected/dashed CTL. */
	private List<FitnessPoint> fitnessSeriesWithPlan(String athleteId, LocalDate from, LocalDate to, LocalDate today) {
		LocalDate start = activityRepository.findEarliestStartDate(athleteId)
				.map(i -> i.atZone(ZoneOffset.UTC).toLocalDate())
				.map(earliest -> earliest.isBefore(from) ? earliest : from)
				.orElse(from);
		if (start.isAfter(to)) return List.of();

		Map<LocalDate, Integer> actual = dailyTss(athleteId, start, today.isBefore(to) ? today : to);
		Map<LocalDate, Integer> planned = to.isAfter(today) ? plannedDailyTss(athleteId, maxDate(start, today.plusDays(1)), to) : Map.of();

		double ctl = 0;
		double atl = 0;
		List<FitnessPoint> series = new ArrayList<>();
		LocalDate day = start;
		while (!day.isAfter(to)) {
			int tss = !day.isAfter(today) ? actual.getOrDefault(day, 0) : planned.getOrDefault(day, 0);
			ctl += (tss - ctl) / CTL_DAYS;
			atl += (tss - atl) / ATL_DAYS;
			if (!day.isBefore(from)) {
				series.add(new FitnessPoint(day, round1(ctl), round1(atl), round1(ctl - atl)));
			}
			day = day.plusDays(1);
		}
		return series;
	}

	// --- weekly aggregates ----------------------------------------------------------------

	private List<LocalDate[]> weekBounds(LocalDate startWeek, int nWeeks) {
		List<LocalDate[]> bounds = new ArrayList<>();
		for (int i = 0; i < nWeeks; i++) {
			LocalDate ws = startWeek.plusWeeks(i);
			bounds.add(new LocalDate[] {ws, ws.plusDays(6)});
		}
		return bounds;
	}

	private RaceBlockResponse.WeekAggregate weeklyAggregate(String athleteId, LocalDate weekStartD, LocalDate weekEndD, LocalDate today) {
		boolean isPlanned = weekStartD.isAfter(today);

		List<Race> racesInWeek = raceRepository.findByAthleteIdAndDateBetween(athleteId, weekStartD, weekEndD);
		List<RaceBlockResponse.WeekRace> racesList = racesInWeek.stream()
				.map(r -> new RaceBlockResponse.WeekRace(r.getId(), r.getName(), r.getPriority().wireValue(), r.getResultTime()))
				.toList();

		Integer tss;
		Integer tssRaces;
		Double runKm;
		Integer runMovingS;
		Integer runCount;
		Double runAvgPace;
		Double runLongKm;
		Double rideKm;
		Integer rideMovingS;
		Integer rideCount;
		Integer rideAvgPowerW;
		Double rideLongKm;
		Double hours;
		Integer sessions;

		if (!isPlanned) {
			List<Activity> activities = activityRepository.findForWeekRange(
					athleteId, weekStartD.atStartOfDay(ZoneOffset.UTC).toInstant(), weekEndD.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
			Set<String> raceActivityIds = racesInWeek.stream()
					.map(Race::getActivity).filter(Objects::nonNull).map(Activity::getId).collect(Collectors.toSet());

			tss = activities.stream().mapToInt(Activity::getTss).sum();
			tssRaces = activities.stream().filter(a -> raceActivityIds.contains(a.getId())).mapToInt(Activity::getTss).sum();

			List<Activity> runs = activities.stream().filter(a -> a.getSport() == Sport.RUN).toList();
			runKm = runs.stream().mapToDouble(Activity::getDistanceKm).sum();
			runMovingS = runs.stream().mapToInt(Activity::getMovingTime).sum();
			runCount = runs.size();
			runAvgPace = runKm > 0 ? runMovingS / runKm : null;
			List<Activity> nonRaceRuns = runs.stream().filter(a -> !raceActivityIds.contains(a.getId())).toList();
			runLongKm = nonRaceRuns.isEmpty() ? null : nonRaceRuns.stream().mapToDouble(Activity::getDistanceKm).max().getAsDouble();

			List<Activity> rides = activities.stream().filter(a -> a.getSport() == Sport.BIKE).toList();
			rideKm = rides.stream().mapToDouble(Activity::getDistanceKm).sum();
			rideMovingS = rides.stream().mapToInt(Activity::getMovingTime).sum();
			rideCount = rides.size();
			List<Activity> poweredRides = rides.stream().filter(a -> a.getAvgPower() != null).toList();
			int poweredMovingS = poweredRides.stream().mapToInt(Activity::getMovingTime).sum();
			rideAvgPowerW = poweredMovingS > 0
					? (int) Math.round(poweredRides.stream().mapToDouble(a -> a.getAvgPower() * (double) a.getMovingTime()).sum() / poweredMovingS)
					: null;
			List<Activity> nonRaceRides = rides.stream().filter(a -> !raceActivityIds.contains(a.getId())).toList();
			rideLongKm = nonRaceRides.isEmpty() ? null : nonRaceRides.stream().mapToDouble(Activity::getDistanceKm).max().getAsDouble();

			hours = activities.stream().mapToInt(Activity::getMovingTime).sum() / 3600.0;
			sessions = activities.size();
		}
		else {
			List<ScheduledWorkout> scheduled = scheduledWorkoutRepository.findByAthleteIdAndDateBetweenOrderByDate(athleteId, weekStartD, weekEndD);
			if (scheduled.isEmpty()) {
				tss = null;
				tssRaces = null;
				runMovingS = null;
				runCount = null;
				rideMovingS = null;
				rideCount = null;
				hours = null;
				sessions = null;
			}
			else {
				tss = scheduled.stream().mapToInt(sw -> sw.getWorkout().getTss()).sum();
				tssRaces = 0;
				List<ScheduledWorkout> runsSw = scheduled.stream().filter(sw -> sw.getWorkout().getSport() == Sport.RUN).toList();
				List<ScheduledWorkout> ridesSw = scheduled.stream().filter(sw -> sw.getWorkout().getSport() == Sport.BIKE).toList();
				runMovingS = runsSw.stream().mapToInt(sw -> sw.getWorkout().getDuration()).sum();
				runCount = runsSw.size();
				rideMovingS = ridesSw.stream().mapToInt(sw -> sw.getWorkout().getDuration()).sum();
				rideCount = ridesSw.size();
				hours = scheduled.stream().mapToInt(sw -> sw.getWorkout().getDuration()).sum() / 3600.0;
				sessions = scheduled.size();
			}
			runKm = null;
			runAvgPace = null;
			runLongKm = null;
			rideKm = null;
			rideAvgPowerW = null;
			rideLongKm = null;
		}

		List<FitnessPoint> series = fitnessSeriesWithPlan(athleteId, weekEndD, weekEndD, today);
		Double ctl = series.isEmpty() ? null : series.get(0).ctl();
		Double atl = series.isEmpty() ? null : series.get(0).atl();

		return new RaceBlockResponse.WeekAggregate(weekStartD, tss, tssRaces, runKm, runMovingS, runCount, runAvgPace, runLongKm,
				rideKm, rideMovingS, rideCount, rideAvgPowerW, rideLongKm, hours, sessions, ctl, atl, isPlanned, racesList);
	}

	// --- race-day form ---------------------------------------------------------------------

	private RaceBlockResponse.RaceDayForm raceDayForm(Race race) {
		LocalDate today = LocalDate.now();
		LocalDate raceWeekStart = weekStart(race.getDate());
		LocalDate prevWeekEnd = raceWeekStart.minusDays(1);

		List<FitnessPoint> prevSeries = fitnessSeriesWithPlan(race.getAthlete().getId(), prevWeekEnd, prevWeekEnd, today);
		double ctl = prevSeries.isEmpty() ? 0.0 : prevSeries.get(0).ctl();
		double atlPrev = prevSeries.isEmpty() ? 0.0 : prevSeries.get(0).atl();

		LocalDate raceWeekEnd = raceWeekStart.plusDays(6);
		Set<String> raceActivityIds = raceRepository.findByAthleteIdAndDateBetween(race.getAthlete().getId(), raceWeekStart, raceWeekEnd)
				.stream().map(Race::getActivity).filter(Objects::nonNull).map(Activity::getId).collect(Collectors.toSet());

		double weekTssExclRace;
		boolean hasData;
		if (!race.getDate().isAfter(today)) {
			int weekTss = dailyTss(race.getAthlete().getId(), raceWeekStart, raceWeekEnd).values().stream().mapToInt(Integer::intValue).sum();
			int raceTss = raceActivityIds.isEmpty()
					? 0
					: activityRepository.findAllById(raceActivityIds).stream().mapToInt(Activity::getTss).sum();
			weekTssExclRace = weekTss - raceTss;
			hasData = weekTss > 0;
		}
		else {
			weekTssExclRace = plannedDailyTss(race.getAthlete().getId(), raceWeekStart, raceWeekEnd).values().stream()
					.mapToInt(Integer::intValue).sum();
			hasData = weekTssExclRace > 0;
		}

		if (!hasData && race.getDate().isAfter(today)) {
			return new RaceBlockResponse.RaceDayForm(round1(ctl), null, null, null, true, true);
		}

		double atl = 0.5 * atlPrev + 0.5 * (weekTssExclRace / 7.0);
		double tsb = ctl - atl;
		return new RaceBlockResponse.RaceDayForm(round1(ctl), round1(atl), round1(tsb), tsbBand(tsb), race.getDate().isAfter(today), false);
	}

	// --- comparison block -------------------------------------------------------------------

	private Race findComparisonRace(Race race) {
		if (race.getDistanceKm() == null) return null;
		double lo = race.getDistanceKm() * (1 - COMPARISON_DISTANCE_TOLERANCE);
		double hi = race.getDistanceKm() * (1 + COMPARISON_DISTANCE_TOLERANCE);
		return raceRepository.findFirstByAthleteIdAndPriorityAndSportAndDistanceKmBetweenAndDateLessThanOrderByDateDesc(
				race.getAthlete().getId(), RacePriority.A, race.getSport(), lo, hi, race.getDate()).orElse(null);
	}

	private List<RaceBlockResponse.WeekAggregate> comparisonWeeks(int nWeeks, Race comparisonRace, LocalDate today) {
		LocalDate compRaceWeek = weekStart(comparisonRace.getDate());
		List<RaceBlockResponse.WeekAggregate> result = new ArrayList<>();
		for (int i = 0; i < nWeeks; i++) {
			int offsetFromEnd = nWeeks - 1 - i;
			LocalDate ws = compRaceWeek.minusWeeks(offsetFromEnd);
			LocalDate we = ws.plusDays(6);
			result.add(weeklyAggregate(comparisonRace.getAthlete().getId(), ws, we, today));
		}
		return result;
	}

	// --- Riegel prediction -------------------------------------------------------------------

	private RaceBlockResponse.Prediction riegelPrediction(Race aRace, LocalDate blockStartWeek, LocalDate blockEndWeek) {
		if (aRace.getSport() != Sport.RUN || aRace.getDistanceKm() == null) return null;
		List<Race> candidates = raceRepository.findByAthleteIdAndPriorityInAndSportAndDateBetweenAndResultTimeIsNotNullAndDistanceKmIsNotNull(
				aRace.getAthlete().getId(), List.of(RacePriority.B, RacePriority.C), Sport.RUN, blockStartWeek, blockEndWeek.plusDays(6));

		double bestS = Double.MAX_VALUE;
		String bestRaceId = null;
		for (Race c : candidates) {
			if (c.getId().equals(aRace.getId())) continue;
			double predictedS = c.getResultTime() * Math.pow(aRace.getDistanceKm() / c.getDistanceKm(), RIEGEL_EXPONENT);
			if (predictedS < bestS) {
				bestS = predictedS;
				bestRaceId = c.getId();
			}
		}
		return bestRaceId == null ? null : new RaceBlockResponse.Prediction((int) Math.round(bestS), bestRaceId);
	}

	// --- key sessions ------------------------------------------------------------------------

	private Double goalPaceSPerKm(Race race) {
		return race.getGoalTime() != null && race.getDistanceKm() != null ? race.getGoalTime() / race.getDistanceKm() : null;
	}

	/** True if some >= window_s-second span covered >= window_s/goalPace km (ran at or faster
	 * than goal pace for at least that long), via a two-pointer scan over the activity's
	 * recorded (t, distanceKm) stream. */
	private boolean bestPaceMeetsGoal(Activity activity, double goalPaceSPerKm) {
		List<Record> rows = recordRepository.findByActivityIdOrderByT(activity.getId());
		if (rows.size() < 2) return false;
		int left = 0;
		for (int right = 0; right < rows.size(); right++) {
			while (rows.get(right).getT() - rows.get(left).getT() > GOAL_PACE_WINDOW_S) {
				left++;
			}
			int spanS = rows.get(right).getT() - rows.get(left).getT();
			double spanKm = orZero(rows.get(right).getDistanceKm()) - orZero(rows.get(left).getDistanceKm());
			if (spanS < GOAL_PACE_WINDOW_S || spanKm <= 0) continue;
			if (spanS / spanKm <= goalPaceSPerKm) return true;
		}
		return false;
	}

	private static double orZero(Double v) {
		return v != null ? v : 0.0;
	}

	private boolean isQualityActivity(Activity activity, Race race) {
		if (activity.getIntensity() != null && activity.getIntensity() >= QUALITY_INTENSITY_FACTOR) return true;
		if (activity.getSport() == Sport.RUN) {
			Double goalPace = goalPaceSPerKm(race);
			if (goalPace != null) return bestPaceMeetsGoal(activity, goalPace);
		}
		return false;
	}

	private String qualityLabel(Activity activity) {
		String workoutType = activity.getWorkout() != null ? activity.getWorkout().getType().toLowerCase() : "";
		if (workoutType.contains("mp") || workoutType.contains("marathon pace")) return "MP";
		if (workoutType.contains("threshold")) return "Threshold";
		if (workoutType.contains("tempo")) return "Tempo";
		if (workoutType.contains("vo2")) return "VO2";
		if (workoutType.contains("race pace")) return "Race pace";
		return "Quality";
	}

	private boolean isLongOrQualityWorkout(Workout workout) {
		if (workout.getDuration() >= LONG_WORKOUT_DURATION_S) return true;
		String type = workout.getType() != null ? workout.getType().toLowerCase() : "";
		return QUALITY_TYPE_KEYWORDS.stream().anyMatch(type::contains);
	}

	/** Activity ids whose best effort ranks in the top 3 by value among ALL of the athlete's
	 * best efforts for that kind/window, where the effort's own date falls in [start, end].
	 * Reuses BestEffortController#capPerWindow directly rather than re-deriving the ranking
	 * rule the best-efforts-ranks endpoint already encodes. */
	private Set<String> top3ActivityIds(String athleteId, LocalDate start, LocalDate end) {
		List<BestEffort> allEfforts = bestEffortRepository.findByAthleteId(athleteId);
		Map<BestEffortKind, List<BestEffort>> byKind = allEfforts.stream().collect(Collectors.groupingBy(BestEffort::getKind));
		Set<String> result = new java.util.HashSet<>();
		for (var entry : byKind.entrySet()) {
			boolean lowerIsBetter = entry.getKey() == BestEffortKind.RUNNING_PACE;
			for (BestEffort e : BestEffortController.capPerWindow(entry.getValue(), lowerIsBetter, 3)) {
				if (!e.getDate().isBefore(start) && !e.getDate().isAfter(end)) {
					result.add(e.getActivity().getId());
				}
			}
		}
		return result;
	}

	private record Candidate(LocalDate date, String name, String activityId, String scheduledWorkoutId, String reason,
			String label, Double km, Integer tss) {}

	private Candidate activityCandidate(Activity a, String reason, String label) {
		return new Candidate(a.getStartDate().atZone(ZoneOffset.UTC).toLocalDate(), a.getName(), a.getId(), null, reason,
				label, a.getDistanceKm(), a.getTss());
	}

	private RaceBlockResponse.KeySessions keySessions(Race race, LocalDate windowStart, LocalDate windowEnd, int cap) {
		String athleteId = race.getAthlete().getId();
		List<Candidate> candidates = new ArrayList<>();
		Set<String> seenActivityIds = new java.util.HashSet<>();

		for (Race r : raceRepository.findByAthleteIdAndDateBetween(athleteId, windowStart, windowEnd)) {
			if (r.getId().equals(race.getId())) continue;
			String activityId = r.getActivity() != null ? r.getActivity().getId() : null;
			Integer tss = r.getActivity() != null ? r.getActivity().getTss() : null;
			candidates.add(new Candidate(r.getDate(), r.getName(), activityId, null, "race",
					r.getPriority().wireValue() + " race", r.getDistanceKm(), tss));
			if (activityId != null) seenActivityIds.add(activityId);
		}

		List<Activity> windowActivities = activityRepository.findForWeekRange(
				athleteId, windowStart.atStartOfDay(ZoneOffset.UTC).toInstant(), windowEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

		Tag keySessionTag = tagRepository.findByAthleteIdAndName(athleteId, "Key session").orElse(null);
		if (keySessionTag != null) {
			Set<String> taggedIds = activityTagRepository.findActivityIdsByTag(keySessionTag);
			for (Activity a : windowActivities) {
				if (seenActivityIds.contains(a.getId()) || !taggedIds.contains(a.getId())) continue;
				candidates.add(activityCandidate(a, "tagged", "Key session"));
				seenActivityIds.add(a.getId());
			}
		}

		Sport longSport = race.getSport() == Sport.BIKE ? Sport.BIKE : Sport.RUN;
		windowActivities.stream()
				.filter(a -> a.getSport() == longSport && !seenActivityIds.contains(a.getId()))
				.filter(a -> !raceRepository.existsByActivityId(a.getId()))
				.sorted(Comparator.comparingDouble(Activity::getDistanceKm).reversed())
				.limit(4)
				.forEach(a -> {
					candidates.add(activityCandidate(a, "long", "Long"));
					seenActivityIds.add(a.getId());
				});

		for (Activity a : windowActivities) {
			if (seenActivityIds.contains(a.getId())) continue;
			if (isQualityActivity(a, race)) {
				candidates.add(activityCandidate(a, "quality", qualityLabel(a)));
				seenActivityIds.add(a.getId());
			}
		}

		for (String activityId : top3ActivityIds(athleteId, windowStart, windowEnd)) {
			if (seenActivityIds.contains(activityId)) continue;
			activityRepository.findById(activityId).ifPresent(a -> {
				candidates.add(activityCandidate(a, "best_effort", "Best effort"));
				seenActivityIds.add(a.getId());
			});
		}

		LocalDate today = LocalDate.now();
		LocalDate plannedStart = maxDate(windowStart, today);
		if (!plannedStart.isAfter(windowEnd)) {
			int plannedCount = 0;
			for (ScheduledWorkout sw : scheduledWorkoutRepository.findByAthleteIdAndDateBetweenOrderByDate(athleteId, plannedStart, windowEnd)) {
				if (plannedCount >= 3) break;
				if (!isLongOrQualityWorkout(sw.getWorkout())) continue;
				candidates.add(new Candidate(sw.getDate(), sw.getWorkout().getName(), null, sw.getId(), "planned",
						"Planned", null, sw.getWorkout().getTss()));
				plannedCount++;
			}
		}

		int totalCandidates = candidates.size();
		candidates.sort(Comparator.<Candidate>comparingInt(c -> REASON_RANK.get(c.reason()))
				.thenComparing(Comparator.comparing(Candidate::date).reversed()));
		List<Candidate> top = candidates.stream().limit(cap).sorted(Comparator.comparing(Candidate::date)).toList();

		List<RaceBlockResponse.KeySessionItem> items = top.stream()
				.map(c -> new RaceBlockResponse.KeySessionItem(c.date(), c.name(), c.activityId(), c.scheduledWorkoutId(),
						c.reason(), c.label(), c.km(), c.tss()))
				.toList();
		return new RaceBlockResponse.KeySessions(totalCandidates, items);
	}

	// --- sweat rate -------------------------------------------------------------------------

	private record SweatRate(double value, String source) {}

	private SweatRate sweatRate(String athleteId) {
		List<Activity> qs = activityRepository.findForSweatRate(athleteId);
		List<Double> rates = new ArrayList<>();
		for (Activity a : qs) {
			double weightLossMl = (a.getStartWeightKg() - a.getEndWeightKg()) * 1000.0;
			double totalLossMl = weightLossMl + a.getFluidsMl();
			double hoursMoved = a.getMovingTime() / 3600.0;
			if (hoursMoved > 0 && totalLossMl > 0) {
				rates.add((totalLossMl / 1000.0) / hoursMoved);
			}
		}
		if (rates.isEmpty()) return new SweatRate(0.9, "default");
		return new SweatRate(round2(rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.9)), "measured");
	}

	// --- top-level assembler -----------------------------------------------------------------

	@Transactional(readOnly = true)
	public RaceBlockResponse getRaceBlock(Race race) {
		// Re-fetched fresh, not race.getAthlete() directly - race may have been loaded by a
		// plain (non-transactional) repository call in the caller (RaceController), under a
		// session this method's own transaction doesn't reattach it to. .getId() alone is
		// always safe off a lazy proxy; touching any other field is not. See backend_java's
		// own CLAUDE.md "JPA entity's lazy association" gotcha.
		User athlete = userRepository.findById(race.getAthlete().getId()).orElseThrow();
		LocalDate today = LocalDate.now();
		int blockWeeksPref = athlete.getRaceBlockWeeks();
		int cap = athlete.getRaceKeySessionCap();

		LocalDate startWeek;
		LocalDate endWeek;
		int nWeeks;
		List<RaceBlockResponse.Phase> phaseList;
		int weeksRequested;
		String truncatedByRaceId;
		RaceBlockResponse.Parent parent = null;

		if (race.getPriority() == RacePriority.A) {
			BlockRange block = aRaceBlockRange(race, blockWeeksPref);
			startWeek = block.startWeek();
			endWeek = block.endWeek();
			nWeeks = block.weeks();
			phaseList = phases(nWeeks);
			weeksRequested = blockWeeksPref;
			truncatedByRaceId = block.truncatedByRaceId();
		}
		else {
			LocalDate raceWeek = weekStart(race.getDate());
			startWeek = raceWeek.minusWeeks(2);
			endWeek = raceWeek.plusWeeks(2);
			nWeeks = 5;
			phaseList = List.of();
			weeksRequested = 5;
			truncatedByRaceId = null;
			Race parentRace = findParentARace(race, blockWeeksPref);
			if (parentRace != null) {
				BlockRange parentBlock = aRaceBlockRange(parentRace, blockWeeksPref);
				int weekNum = (int) (java.time.temporal.ChronoUnit.DAYS.between(parentBlock.startWeek(), raceWeek) / 7) + 1;
				parent = new RaceBlockResponse.Parent(parentRace.getId(), weekNum, parentBlock.weeks());
			}
		}

		List<RaceBlockResponse.WeekAggregate> weekly = weekBounds(startWeek, nWeeks).stream()
				.map(b -> weeklyAggregate(athlete.getId(), b[0], b[1], today))
				.toList();
		SweatRate sweat = sweatRate(athlete.getId());

		RaceBlockResponse.Comparison comparison = null;
		RaceBlockResponse.Prediction prediction = null;
		RaceBlockResponse.Durability durability = null;

		if (race.getPriority() == RacePriority.A) {
			Race comparisonRace = findComparisonRace(race);
			comparison = comparisonRace != null
					? new RaceBlockResponse.Comparison(comparisonRace.getId(), comparisonWeeks(nWeeks, comparisonRace, today))
					: null;
			prediction = riegelPrediction(race, startWeek, endWeek);

			if (race.getSport() == Sport.RUN || race.getSport() == Sport.BIKE) {
				var current = durabilityQueryService.getSectionForRange(athlete, race.getSport(), startWeek, endWeek);
				var comparisonSection = comparisonRace != null
						? durabilityQueryService.getSectionForRange(athlete, race.getSport(),
								aRaceBlockRange(comparisonRace, blockWeeksPref).startWeek(), aRaceBlockRange(comparisonRace, blockWeeksPref).endWeek())
						: null;
				durability = new RaceBlockResponse.Durability(race.getSport() == Sport.BIKE ? "ride" : "run", current, comparisonSection);
			}
		}

		return new RaceBlockResponse(startWeek, endWeek, weeksRequested, truncatedByRaceId, phaseList, parent, weekly,
				raceDayForm(race), comparison, prediction, keySessions(race, startWeek, endWeek, cap), durability,
				sweat.value(), sweat.source());
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
