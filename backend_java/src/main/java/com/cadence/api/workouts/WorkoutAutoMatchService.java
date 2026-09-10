package com.cadence.api.workouts;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityTag;
import com.cadence.api.activities.ActivityTagRepository;
import com.cadence.api.activities.LapDerivationService;
import com.cadence.api.activities.Tag;
import com.cadence.api.activities.TagOrigin;
import com.cadence.api.activities.TagRepository;
import com.cadence.api.athletes.LapSource;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.scheduling.ScheduledWorkout;
import com.cadence.api.scheduling.ScheduledWorkoutRepository;
import com.cadence.api.scheduling.ScheduledWorkoutStatus;
import com.cadence.api.users.User;
import com.cadence.api.webhooks.ScheduledWorkoutMatchedEvent;
import com.cadence.api.workouts.WorkoutMatchScanService.RankedWorkout;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links a same-day, same-sport, still-planned {@link ScheduledWorkout} to a newly-ingested
 * activity, if one exists - the actual matching logic behind {@code WorkoutMatchTasklet},
 * extracted into a plain injectable service (rather than living directly in the Spring Batch
 * tasklet) so it's directly unit-testable. Not to be confused with {@link WorkoutMatchService},
 * which is read-only (backs {@code GET /v1/workouts/{id}/matches}) and doesn't perform matching.
 */
@Service
public class WorkoutAutoMatchService {

	private static final Logger log = LoggerFactory.getLogger(WorkoutAutoMatchService.class);

	// Matches ParseFileTasklet.DATE_FORMAT - the same date format the default
	// "{sport} on {date}" activity name already uses.
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final ActivityRepository activityRepository;
	private final ScheduledWorkoutRepository scheduledWorkoutRepository;
	private final TagRepository tagRepository;
	private final ActivityTagRepository activityTagRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final LapDerivationService lapDerivationService;
	private final WorkoutMatchScanService workoutMatchScanService;

	public WorkoutAutoMatchService(ActivityRepository activityRepository,
			ScheduledWorkoutRepository scheduledWorkoutRepository, TagRepository tagRepository,
			ActivityTagRepository activityTagRepository, ApplicationEventPublisher eventPublisher,
			LapDerivationService lapDerivationService, WorkoutMatchScanService workoutMatchScanService) {
		this.activityRepository = activityRepository;
		this.scheduledWorkoutRepository = scheduledWorkoutRepository;
		this.tagRepository = tagRepository;
		this.activityTagRepository = activityTagRepository;
		this.eventPublisher = eventPublisher;
		this.lapDerivationService = lapDerivationService;
		this.workoutMatchScanService = workoutMatchScanService;
	}

	@Transactional
	public void attemptMatch(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		User athlete = activity.getAthlete();
		LocalDate date = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();

		List<ScheduledWorkout> candidates = scheduledWorkoutRepository.findMatchCandidates(athlete.getId(), date, activity.getSport());
		if (candidates.isEmpty()) {
			return;
		}
		ScheduledWorkout candidate = resolveMatchCandidate(candidates, activity);
		if (candidate == null) {
			return;
		}
		candidate.setActivity(activity);
		candidate.setStatus(ScheduledWorkoutStatus.COMPLETED);
		scheduledWorkoutRepository.save(candidate);

		Workout workout = candidate.getWorkout();
		activity.setWorkout(workout);
		if (athlete.isRenameMatchedActivities()) {
			activity.setName(matchedActivityName(workout, activity, athlete));
		}
		activityRepository.save(activity);

		Tag tag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), "Auto-matched").orElseGet(() -> {
			Tag created = new Tag();
			created.setAthlete(athlete);
			created.setName("Auto-matched");
			created.setOrigin(TagOrigin.AUTO);
			return tagRepository.save(created);
		});
		if (!activityTagRepository.existsByActivityIdAndTagId(activity.getId(), tag.getId())) {
			ActivityTag link = new ActivityTag();
			link.setActivity(activity);
			link.setTag(tag);
			activityTagRepository.save(link);
		}
		if (athlete.isCopyMatchedWorkoutTags()) {
			for (String workoutTagName : workout.getTags()) {
				if (workoutTagName.isBlank()) {
					continue;
				}
				// MANUAL, not AUTO: these are the workout's own descriptive tags (e.g. "road",
				// "marathon") - ordinary content that happens to be copied automatically, not a
				// system marker like "Auto-matched" above. AUTO would permanently block removal
				// (see TagService.detachTag) on every activity that ever reuses this tag name.
				Tag workoutTag = tagRepository.findByAthleteIdAndNameIgnoreCase(athlete.getId(), workoutTagName)
						.orElseGet(() -> {
							Tag created = new Tag();
							created.setAthlete(athlete);
							created.setName(workoutTagName);
							created.setOrigin(TagOrigin.MANUAL);
							return tagRepository.save(created);
						});
				if (!activityTagRepository.existsByActivityIdAndTagId(activity.getId(), workoutTag.getId())) {
					ActivityTag link = new ActivityTag();
					link.setActivity(activity);
					link.setTag(workoutTag);
					activityTagRepository.save(link);
				}
			}
		}
		if (athlete.getLapSource() == LapSource.MATCHED_WORKOUT) {
			lapDerivationService.replaceLapsWithDerived(activity, workout);
		}
		eventPublisher.publishEvent(new ScheduledWorkoutMatchedEvent(candidate.getId(), athlete.getId()));
	}

	/** Picks which of several same-day, same-sport, still-planned candidates this activity
	 * actually belongs to. A single candidate needs no disambiguation - the common case, and the
	 * only one that existed before this method did. With more than one, ranks their workouts by
	 * correlation against the activity's actual power stream (rankWorkoutsForActivity) and takes
	 * the best; if none of them produce a usable correlation (no power-scannable candidate, or
	 * the activity has no power data), returns {@code null} rather than guessing - a silent
	 * wrong auto-match is worse than a visibly-unmatched pair, since the wrong guess looks
	 * resolved (tagged "Auto-matched") while an unmatched pair gets noticed.
	 */
	private ScheduledWorkout resolveMatchCandidate(List<ScheduledWorkout> candidates, Activity activity) {
		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		String candidateIds = candidates.stream().map(ScheduledWorkout::getId).collect(Collectors.joining(", "));
		List<Workout> workouts = candidates.stream().map(ScheduledWorkout::getWorkout).toList();
		List<RankedWorkout> ranked = workoutMatchScanService.rankWorkoutsForActivity(workouts, activity);
		if (ranked.isEmpty()) {
			log.warn(
					"Ambiguous workout match for activity {}: {} same-day/sport candidates ({}), none correlated - "
							+ "leaving unmatched for manual resolution.",
					activity.getId(), candidates.size(), candidateIds);
			return null;
		}

		RankedWorkout best = ranked.get(0);
		ScheduledWorkout winner = candidates.stream()
				.filter(c -> c.getWorkout().getId().equals(best.workout().getId()))
				.findFirst()
				.orElseThrow();
		log.warn(
				"Ambiguous workout match for activity {}: {} same-day/sport candidates ({}), resolved to {} via "
						+ "correlation (r={}).",
				activity.getId(), candidates.size(), candidateIds, winner.getId(), best.correlation());
		return winner;
	}

	/** athlete.isRenameMatchedActivities()'s naming. */
	private String matchedActivityName(Workout workout, Activity activity, User athlete) {
		if (athlete.isAppendMatchDateToName()) {
			return workout.getName() + " - " + DATE_FORMAT.format(activity.getStartDate().atZone(ZoneOffset.UTC));
		}
		return workout.getName();
	}
}
