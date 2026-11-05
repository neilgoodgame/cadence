package com.cadence.api.activities;

import com.cadence.api.activities.dto.OverlapPairResponse;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.gear.GearMileageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #498: runs once per newly-ingested activity (see {@code ParseFileTasklet}) to detect and,
 * depending on the resolved {@link OverlapHandling} mode, resolve same-ride-twice duplicates
 * against the athlete's existing activities.
 *
 * <p>Detection itself always reuses {@link ActivityOverlapService} (the same query backing
 * {@code GET /v1/activities/overlaps}) pinned to the one activity just ingested, with the
 * endpoint's own defaults (50% minimum overlap, same sport on both sides, already-resolved pairs
 * excluded) - there's no separate ingest-time tolerance to keep in sync.
 *
 * <p>{@code MARK} picks a primary with {@link #choosePrimary}: the issue's own rule is "the Zwift
 * copy wins; otherwise prefer the athlete's power-source setting (#499), then more sensor
 * channels" - #499 doesn't exist yet, so this only implements the Zwift and sensor-channel steps,
 * with a deterministic (start date, then id) tie-break after that so the choice is never random.
 * {@code FLAG} - and {@code MERGE}, until #501 ships a real merge - detect and report but write
 * nothing; the pair is already visible as {@code unresolved} through the overlap query itself,
 * so there's no separate "flagged" state to persist. */
@Service
public class ActivityOverlapDetectionService {

	/** Zwift + Garmin pairs are almost always exactly one real duplicate; this just bounds a
	 * pathological case (e.g. several short re-recorded attempts) from doing unbounded work. */
	private static final int MAX_PAIRS_PER_ACTIVITY = 10;

	private final ActivityOverlapService overlapService;
	private final ActivityRepository activityRepository;
	private final GearMileageService gearMileageService;

	public ActivityOverlapDetectionService(ActivityOverlapService overlapService, ActivityRepository activityRepository,
			GearMileageService gearMileageService) {
		this.overlapService = overlapService;
		this.activityRepository = activityRepository;
		this.gearMileageService = gearMileageService;
	}

	public record OverlapResolution(String otherActivityId, OverlapAction action) {
	}

	@Transactional
	public List<OverlapResolution> detectAndResolve(Activity activity, OverlapHandling mode) {
		if (mode == OverlapHandling.OFF || isMultisportLinked(activity)) {
			return List.of();
		}

		CursorPage<OverlapPairResponse> page = overlapService.findOverlaps(
				activity.getAthlete().getId(), activity.getId(), null, false, null, null, null, null, null, null,
				List.of(), null, null, false, null, MAX_PAIRS_PER_ACTIVITY);

		List<OverlapResolution> results = new ArrayList<>();
		for (OverlapPairResponse pair : page.data()) {
			if (!"unresolved".equals(pair.status())) {
				continue; // already duplicate/merged - nothing new to do
			}
			// activityId pins the anchor to `activity` itself, so the other side is always `other`.
			results.add(resolvePair(activity, pair.other().id(), mode));
		}
		return results;
	}

	/** Resolves one already-found unresolved pair - shared by {@link #detectAndResolve} (anchored
	 * to one newly-ingested activity) and the issue #498 backfill job ({@code
	 * OverlapBackfillRunner}/{@code --backfill-overlaps}), which instead walks every unresolved
	 * pair the overlap query returns for an athlete's whole history. */
	@Transactional
	public OverlapResolution resolvePair(String activityId, String otherActivityId, OverlapHandling mode) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new IllegalStateException("No such activity: " + activityId));
		return resolvePair(activity, otherActivityId, mode);
	}

	private OverlapResolution resolvePair(Activity activity, String otherId, OverlapHandling mode) {
		if (mode == OverlapHandling.MARK && mark(activity, otherId)) {
			return new OverlapResolution(otherId, OverlapAction.MARKED);
		}
		// FLAG, or MERGE falling back to FLAG (see class Javadoc) - also where MARK lands when the
		// pair turned out not to be linkable (see canLink).
		return new OverlapResolution(otherId, OverlapAction.FLAGGED);
	}

	/** @return true if {@code activity} and {@code other} were linked (one of them saved as the
	 * other's duplicate); false if the pair couldn't be safely linked (see {@link #canLink}), in
	 * which case the caller reports it as merely flagged instead. */
	private boolean mark(Activity activity, String otherId) {
		Activity other = activityRepository.findById(otherId).orElse(null);
		if (other == null) {
			return false;
		}
		boolean activityIsPrimary = choosePrimary(activity, other) == activity;
		Activity primary = activityIsPrimary ? activity : other;
		Activity duplicate = activityIsPrimary ? other : activity;
		if (!canLink(duplicate, primary)) {
			return false;
		}
		duplicate.setPrimaryActivity(primary);
		Activity saved = activityRepository.save(duplicate);
		String bikeId = saved.getBike() == null ? null : saved.getBike().getId();
		String shoeId = saved.getShoe() == null ? null : saved.getShoe().getId();
		gearMileageService.applyDuplicateChange(bikeId, shoeId, saved.getDistanceKm(), false, true);
		return true;
	}

	/** Mirrors {@code ActivityService.resolvePrimaryActivity}'s validation for the manual
	 * {@code PATCH .../primary_activity_id} path, now that {@link #choosePrimary} has settled
	 * which side would play which role - same athlete is already guaranteed by the overlap query
	 * itself. Deliberately asymmetric: {@code primary} having other duplicates already pointing
	 * to it is fine (the normal state for an established primary); only {@code duplicate} having
	 * duplicates of its own, or either side already being linked some other way, blocks it. */
	private boolean canLink(Activity duplicate, Activity primary) {
		if (duplicate.getPrimaryActivity() != null || primary.getPrimaryActivity() != null) {
			return false; // one side is already a duplicate of some third activity
		}
		if (isMultisportLinked(duplicate) || isMultisportLinked(primary)) {
			return false;
		}
		return !activityRepository.existsByPrimaryActivityId(duplicate.getId());
	}

	private boolean isMultisportLinked(Activity activity) {
		return activity.getSport() == Sport.MULTISPORT || activity.getSport() == Sport.TRANSITION
				|| activity.getParentActivity() != null;
	}

	/** @return whichever of the two should survive as the primary. Zwift wins outright (it
	 * carries the route, virtual GPS track, distance/speed and elevation - see issue #498); failing
	 * that, the side with more populated sensor summary fields (power/HR/cadence/core temp) wins;
	 * failing that, the earlier start date, then the lower id, so the result is always
	 * deterministic rather than depending on which copy happened to import first. */
	static Activity choosePrimary(Activity a, Activity b) {
		boolean aZwift = isZwift(a);
		boolean bZwift = isZwift(b);
		if (aZwift != bZwift) {
			return aZwift ? a : b;
		}
		int aSensors = sensorChannelCount(a);
		int bSensors = sensorChannelCount(b);
		if (aSensors != bSensors) {
			return aSensors > bSensors ? a : b;
		}
		if (!a.getStartDate().equals(b.getStartDate())) {
			return a.getStartDate().isBefore(b.getStartDate()) ? a : b;
		}
		return a.getId().compareTo(b.getId()) <= 0 ? a : b;
	}

	private static boolean isZwift(Activity activity) {
		return activity.getDevice() != null && activity.getDevice().toLowerCase(Locale.ROOT).startsWith("zwift");
	}

	private static int sensorChannelCount(Activity activity) {
		int count = 0;
		if (activity.getAvgPower() != null) count++;
		if (activity.getAvgHr() != null) count++;
		if (activity.getAvgCadence() != null) count++;
		if (activity.getAvgCoreTemp() != null) count++;
		return count;
	}
}
