package com.cadence.api.races;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.races.dto.RaceUpdateRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RaceService {

	private final RaceRepository raceRepository;
	private final ActivityRepository activityRepository;
	private final CourseRepository courseRepository;
	private final RaceBlockService raceBlockService;
	private final UserRepository userRepository;
	private final TagRepository tagRepository;

	public RaceService(RaceRepository raceRepository, ActivityRepository activityRepository,
			CourseRepository courseRepository, RaceBlockService raceBlockService, UserRepository userRepository,
			TagRepository tagRepository) {
		this.raceRepository = raceRepository;
		this.activityRepository = activityRepository;
		this.courseRepository = courseRepository;
		this.userRepository = userRepository;
		this.raceBlockService = raceBlockService;
		this.tagRepository = tagRepository;
	}

	public List<Race> listRaces(String athleteId) {
		return raceRepository.findByAthleteIdOrderByDateAsc(athleteId);
	}

	public Race getRace(String id) {
		return raceRepository.findById(id).orElseThrow(() -> new NotFoundException("No such race."));
	}

	@Transactional
	public Race createRace(User athlete, RaceCreateRequest req) {
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName(req.name());
		race.setDate(LocalDate.parse(req.date()));
		race.setSport(parseSport(req.sport()));
		race.setPriority(req.priority() != null ? RacePriority.fromWireValue(req.priority()) : RacePriority.A);
		race.setCourse(resolveCourse(req.courseId()));
		race.setDistanceKm(req.distanceKm());
		race.setGoalTime(parseDuration(req.goalTime()));
		race.setResultTime(parseDuration(req.resultTime()));
		race.setTargetPower(req.targetPower());
		race.setActivity(resolveActivity(req.activityId()));
		race.setUrl(req.url());
		race.setResultsUrl(req.resultsUrl());
		race.setNotes(req.notes() != null ? req.notes() : "");
		race.setKeySessionTag(resolveTag(req.keySessionTagId()));
		race.setBlockWeeksRequested(req.blockWeeksRequested());
		race.setBaseWeeksRequested(req.baseWeeksRequested());
		race.setBuildWeeksRequested(req.buildWeeksRequested());
		race.setPeakWeeksRequested(req.peakWeeksRequested());
		race.setTaperWeeksRequested(req.taperWeeksRequested());
		validateBlockPlan(race);
		validateSufficientWeeksRemain(req.blockWeeksRequested(), race.getDate());
		return raceRepository.save(race);
	}

	@Transactional
	public Race updateRace(Race race, RaceUpdateRequest req) {
		if (req.name() != null) race.setName(req.name());
		if (req.date() != null) race.setDate(LocalDate.parse(req.date()));
		if (req.sport() != null) race.setSport(parseSport(req.sport()));
		if (req.priority() != null) race.setPriority(RacePriority.fromWireValue(req.priority()));
		if (req.courseId() != null) race.setCourse(req.courseId().isEmpty() ? null : resolveCourse(req.courseId()));
		if (req.distanceKm() != null) race.setDistanceKm(req.distanceKm());
		if (req.goalTime() != null) race.setGoalTime(parseDuration(req.goalTime()));
		if (req.resultTime() != null) race.setResultTime(parseDuration(req.resultTime()));
		if (req.targetPower() != null) race.setTargetPower(req.targetPower());
		if (req.activityId() != null) race.setActivity(resolveActivity(req.activityId()));
		if (req.url() != null) race.setUrl(req.url().isEmpty() ? null : req.url());
		if (req.resultsUrl() != null) race.setResultsUrl(req.resultsUrl().isEmpty() ? null : req.resultsUrl());
		if (req.notes() != null) race.setNotes(req.notes());
		if (req.isPublic() != null) race.setPublic(req.isPublic());
		if (req.keySessionTagId() != null) race.setKeySessionTag(req.keySessionTagId().isEmpty() ? null : resolveTag(req.keySessionTagId()));
		if (req.blockWeeksRequested() != null) race.setBlockWeeksRequested(req.blockWeeksRequested());
		if (req.baseWeeksRequested() != null) race.setBaseWeeksRequested(req.baseWeeksRequested());
		if (req.buildWeeksRequested() != null) race.setBuildWeeksRequested(req.buildWeeksRequested());
		if (req.peakWeeksRequested() != null) race.setPeakWeeksRequested(req.peakWeeksRequested());
		if (req.taperWeeksRequested() != null) race.setTaperWeeksRequested(req.taperWeeksRequested());
		validateBlockPlan(race);
		validateSufficientWeeksRemain(req.blockWeeksRequested(), race.getDate());
		return raceRepository.save(race);
	}

	/** block_weeks_requested and the four phase-length fields (A races only) must either all
	 * be absent, or form one internally-consistent plan: all four phase fields present
	 * together, each in a sane range, and summing to exactly block_weeks_requested. Called
	 * after every field has already been applied to `race` (both on create and on a partial
	 * update), so it always checks the race's *resulting* state. */
	private static final int MAX_BLOCK_PLAN_WEEKS = 52;

	private void validateBlockPlan(Race race) {
		Integer blockWeeks = race.getBlockWeeksRequested();
		Integer base = race.getBaseWeeksRequested();
		Integer build = race.getBuildWeeksRequested();
		Integer peak = race.getPeakWeeksRequested();
		Integer taper = race.getTaperWeeksRequested();
		boolean anyPhaseSet = base != null || build != null || peak != null || taper != null;
		boolean allPhasesSet = base != null && build != null && peak != null && taper != null;

		if (anyPhaseSet && !allPhasesSet) {
			throw new ValidationException(
					"baseWeeksRequested/buildWeeksRequested/peakWeeksRequested/taperWeeksRequested must all be "
							+ "set together, or none of them.",
					"baseWeeksRequested");
		}
		if ((blockWeeks != null || anyPhaseSet) && race.getPriority() != RacePriority.A) {
			throw new ValidationException("Training-block length and phase weeks are for A races only.", "blockWeeksRequested");
		}
		record Field(String name, Integer value) {}
		// LinkedHashMap.entrySet()/Map.of() both reject null values outright (Map.of() throws
		// immediately at construction, before any entry is even inspected) - these fields are
		// null in the common (no block plan set) case, so a plain list of pairs is used instead.
		for (Field field : List.of(
				new Field("blockWeeksRequested", blockWeeks), new Field("baseWeeksRequested", base),
				new Field("buildWeeksRequested", build), new Field("peakWeeksRequested", peak),
				new Field("taperWeeksRequested", taper))) {
			if (field.value() != null && (field.value() < 1 || field.value() > MAX_BLOCK_PLAN_WEEKS)) {
				throw new ValidationException("Must be between 1 and " + MAX_BLOCK_PLAN_WEEKS + ".", field.name());
			}
		}
		if (allPhasesSet) {
			if (blockWeeks == null) {
				throw new ValidationException("Required when base/build/peak/taper weeks are set.", "blockWeeksRequested");
			}
			int total = base + build + peak + taper;
			if (total != blockWeeks) {
				throw new ValidationException(
						"base+build+peak+taper (" + total + ") must equal blockWeeksRequested (" + blockWeeks + ").",
						"baseWeeksRequested");
			}
		}
	}

	/** Only checked against the *raw* request value (not the race's post-merge state, unlike
	 * validateBlockPlan above) - so it only fires when blockWeeksRequested is actually being
	 * set/changed by *this* request, not on every subsequent unrelated edit to an older race
	 * whose plan no longer fits as "today" marches forward. */
	private void validateSufficientWeeksRemain(Integer requestedBlockWeeks, LocalDate raceDate) {
		if (requestedBlockWeeks == null) return;
		Integer weeksUntil = weeksUntil(raceDate);
		if (weeksUntil != null && requestedBlockWeeks > weeksUntil) {
			throw new ValidationException(
					"Only " + weeksUntil + " week(s) remain before this race - blockWeeksRequested must be "
							+ weeksUntil + " or fewer.",
					"blockWeeksRequested");
		}
	}

	/** Calendar weeks between this week (today's own Monday) and the race's week, inclusive -
	 * null if the race is today or already in the past, where "how many weeks of runway are
	 * left" isn't a meaningful question. Monday-aligned the same way RaceBlockService's own
	 * weekStart is, so this lines up with what GET /v1/races/{id}/block will actually show. */
	private static Integer weeksUntil(LocalDate raceDate) {
		LocalDate today = LocalDate.now();
		if (!raceDate.isAfter(today)) return null;
		LocalDate raceWeek = raceDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate thisWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		return (int) (java.time.temporal.ChronoUnit.DAYS.between(thisWeek, raceWeek) / 7) + 1;
	}

	@Transactional
	public void deleteRace(String id) {
		raceRepository.deleteById(id);
	}

	/**
	 * {@code @Transactional}, and re-fetches the athlete fresh rather than trusting
	 * {@code race.getAthlete()} - {@code race} may have been loaded by a plain (non-
	 * transactional) repository call in the caller (e.g. RaceController.listRaces), in a
	 * session this method's own transaction doesn't reattach it to. {@code .getId()} alone is
	 * always safe off a lazy proxy regardless; touching any other field (getRaceBlockWeeks())
	 * is not. See backend_java's own CLAUDE.md "JPA entity's lazy association" gotcha.
	 */
	@Transactional(readOnly = true)
	public RaceResponse toResponse(Race race) {
		User athlete = userRepository.findById(race.getAthlete().getId()).orElseThrow();
		String parentRaceId = null;
		LocalDate blockStart = null;
		Integer blockWeeks = null;

		if (race.getPriority() == RacePriority.A) {
			RaceBlockService.BlockRange block = raceBlockService.aRaceBlockRange(race, raceBlockService.resolveBlockWeeks(race, athlete));
			blockStart = block.startWeek();
			blockWeeks = block.weeks();
		}
		else {
			Race parent = raceBlockService.findParentARace(race, athlete);
			if (parent != null) {
				parentRaceId = parent.getId();
				RaceBlockService.BlockRange block = raceBlockService.aRaceBlockRange(parent, raceBlockService.resolveBlockWeeks(parent, athlete));
				blockStart = block.startWeek();
				blockWeeks = block.weeks();
			}
		}

		return new RaceResponse(
				race.getId(),
				race.getAthlete().getId(),
				race.getName(),
				race.getDate().toString(),
				race.getSport() != null ? race.getSport().wireValue() : "",
				race.getPriority().wireValue(),
				race.getCourse() != null ? race.getCourse().getId() : null,
				race.getDistanceKm(),
				formatDuration(race.getGoalTime()),
				formatDuration(race.getResultTime()),
				race.getActivity() != null ? race.getActivity().getId() : null,
				race.getUrl(),
				race.getResultsUrl(),
				race.getNotes(),
				parentRaceId,
				blockStart,
				blockWeeks,
				race.getKeySessionTag() != null ? race.getKeySessionTag().getId() : null,
				race.getTargetPower(),
				race.isPublic(),
				race.getBlockWeeksRequested(),
				race.getBaseWeeksRequested(),
				race.getBuildWeeksRequested(),
				race.getPeakWeeksRequested(),
				race.getTaperWeeksRequested()
		);
	}

	private Course resolveCourse(String courseId) {
		if (courseId == null || courseId.isBlank()) return null;
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("No such course."));
	}

	private Tag resolveTag(String tagId) {
		if (tagId == null || tagId.isBlank()) return null;
		return tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("No such tag."));
	}

	// "H:MM:SS" or "HH:MM:SS" → total seconds; null/blank → null
	private static Integer parseDuration(String s) {
		if (s == null || s.isBlank()) return null;
		String[] parts = s.split(":");
		if (parts.length != 3) return null;
		int h = Integer.parseInt(parts[0]);
		int m = Integer.parseInt(parts[1]);
		int sec = Integer.parseInt(parts[2]);
		return h * 3600 + m * 60 + sec;
	}

	// seconds → "H:MM:SS"; null → null
	static String formatDuration(Integer seconds) {
		if (seconds == null) return null;
		int h = seconds / 3600;
		int m = (seconds % 3600) / 60;
		int s = seconds % 60;
		return String.format("%d:%02d:%02d", h, m, s);
	}

	private static Sport parseSport(String s) {
		if (s == null || s.isBlank()) return null;
		try {
			return Sport.fromWireValue(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private Activity resolveActivity(String activityId) {
		if (activityId == null || activityId.isBlank()) return null;
		return activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
	}
}
