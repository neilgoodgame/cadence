package com.cadence.api.activities;

import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.AlignmentResult;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.Sample;
import com.cadence.api.athletes.BikePowerSource;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #503 (phase 1): keeps a second power stream alongside an activity instead of #501's
 * merge overwriting one copy with the other - {@link #attachPowerStream} (the {@code attach}
 * merge {@code copyMode}, called from {@link ActivityMergeService#runMerge}) and {@link
 * #setPrimarySource} (swap which stream is primary, callable any time afterward, not just right
 * after an attach). Phase 1 only ever creates/swaps {@code power} sources - see the field
 * validation in both methods. Mirrors backend's activities/activity_source_service.py - keep
 * the two in sync when changing either.
 */
@Service
public class ActivitySourceService {

	private final ActivityRepository activityRepository;
	private final RecordRepository recordRepository;
	private final ActivitySourceRepository sourceRepository;
	private final ActivityStreamRepository streamRepository;
	private final ActivityMergePostProcessingService postProcessingService;

	public ActivitySourceService(ActivityRepository activityRepository, RecordRepository recordRepository,
			ActivitySourceRepository sourceRepository, ActivityStreamRepository streamRepository,
			ActivityMergePostProcessingService postProcessingService) {
		this.activityRepository = activityRepository;
		this.recordRepository = recordRepository;
		this.sourceRepository = sourceRepository;
		this.streamRepository = streamRepository;
		this.postProcessingService = postProcessingService;
	}

	public record AttachResult(String sourceId, int offsetSeconds, Double alignmentScore, boolean becamePrimary,
			int copied, int nulled) {
	}

	/** Zwift (trainer power, read after drivetrain losses) vs everything else (treated as
	 * pedals, by elimination) - the same heuristic {@code MergeRuleService}'s built-in bike rule
	 * and {@code ActivityOverlapDetectionService#isZwift} already use. Phase 1 deliberately
	 * reuses this rather than adding new FIT device_info parsing - see issue #503's own Phase 1
	 * scope ("covers the Zwift + Garmin case"); real ANT+ device-type classification for
	 * non-Zwift devices is a later refinement. */
	static BikePowerSource classify(String device) {
		return device != null && device.toLowerCase(Locale.ROOT).startsWith("zwift") ? BikePowerSource.TRAINER
				: BikePowerSource.PEDALS;
	}

	/** An activity's own currently-primary power classification - its own bikePowerSource once
	 * that's been set by a previous attach/swap, or else classified from its device (the file's
	 * own reading, never swapped). */
	private static BikePowerSource currentPrimarySource(Activity activity) {
		return activity.getBikePowerSource() != null ? activity.getBikePowerSource() : classify(activity.getDevice());
	}

	/** Attaches {@code source}'s power stream onto {@code target} as a non-primary {@link
	 * ActivityStream}, aligned to target's own timeline using the same cross-correlation {@link
	 * ActivityMergeService#runMerge} uses for {@code overwrite}. If the athlete's {@code
	 * bikePowerSource} preference says the attached stream should be primary instead of target's
	 * current one, immediately swaps via {@link #setPrimarySource} before returning - the
	 * athlete can always swap back later. {@code preview=true} computes and returns the result
	 * without writing anything (no transaction-rollback trick needed here, unlike {@code
	 * overwrite} - nothing is written unless {@code preview} is false, so there's nothing to
	 * undo). */
	@Transactional
	public AttachResult attachPowerStream(String targetId, String sourceId, Integer offsetSecondsOverride, boolean preview) {
		Activity target = activityRepository.findById(targetId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		Activity source = activityRepository.findById(sourceId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		if (target.getId().equals(source.getId())) {
			throw new ValidationException("An activity cannot be merged with itself.", "source_id");
		}
		if (!target.getAthlete().getId().equals(source.getAthlete().getId())) {
			throw new ValidationException("Both activities must belong to the same athlete.", "source_id");
		}

		List<Record> targetRows = recordRepository.findByActivityIdOrderByT(targetId);
		List<Record> sourceRows = recordRepository.findByActivityIdOrderByT(sourceId);
		List<Sample> targetSamples = targetRows.stream()
				.map(r -> new Sample(r.getT(), r.getCadence(), r.getPower())).toList();
		List<Sample> sourceSamples = sourceRows.stream()
				.map(r -> new Sample(r.getT(), r.getCadence(), r.getPower())).toList();

		int offsetSeconds;
		Double alignmentScore;
		if (offsetSecondsOverride != null) {
			offsetSeconds = offsetSecondsOverride;
			alignmentScore = null;
		}
		else {
			int initialOffset = (int) Duration.between(target.getStartDate(), source.getStartDate()).toSeconds();
			AlignmentResult result = ActivityMergeAlignmentCalculator.align(targetSamples, sourceSamples, initialOffset);
			offsetSeconds = result.offsetSeconds();
			alignmentScore = result.score();
		}

		// Build the attached array on the target's own timeline (t0..t0+n-1, matching Record's
		// t) - source's power at target-local t is whatever the source recorded at its own local
		// time (t - offsetSeconds), same lookup direction ActivityMergeApplyService's overwrite
		// path uses.
		Map<Integer, Integer> sourcePowerByT = sourceRows.stream()
				.filter(r -> r.getPower() != null)
				.collect(Collectors.toMap(Record::getT, Record::getPower, (a, b) -> b));
		int t0 = targetRows.stream().mapToInt(Record::getT).min().orElse(0);
		int tMax = targetRows.stream().mapToInt(Record::getT).max().orElse(t0);
		Short[] values = new Short[tMax - t0 + 1];
		int copied = 0;
		int nulled = 0;
		for (int t = t0; t <= tMax; t++) {
			Integer sourceValue = sourcePowerByT.get(t - offsetSeconds);
			if (sourceValue != null) {
				values[t - t0] = sourceValue.shortValue();
				copied++;
			}
			else {
				nulled++;
			}
		}

		if (preview) {
			return new AttachResult(null, offsetSeconds, alignmentScore, false, copied, nulled);
		}

		ActivitySource activitySource = new ActivitySource();
		activitySource.setActivity(target);
		activitySource.setLabel(source.getName());
		activitySource.setDevice(source.getDevice());
		activitySource.setSensorKind("power");
		activitySource.setOrigin(ActivitySourceOrigin.ATTACHED);
		activitySource = sourceRepository.save(activitySource);

		ActivityStream stream = new ActivityStream();
		stream.setActivity(target);
		stream.setField("power");
		stream.setSource(activitySource);
		stream.setT0(t0);
		stream.setStreamValues(values);
		stream.setPrimaryOf(false);
		streamRepository.save(stream);

		User athlete = target.getAthlete();
		BikePowerSource preference = athlete.getBikePowerSource();
		boolean becamePrimary = false;
		if (preference != BikePowerSource.ANY && classify(source.getDevice()) == preference
				&& currentPrimarySource(target) != preference) {
			setPrimarySource(targetId, "power", activitySource.getId());
			becamePrimary = true;
		}

		return new AttachResult(activitySource.getId(), offsetSeconds, alignmentScore, becamePrimary, copied, nulled);
	}

	/** Swaps {@code field} on {@code activityId} with the named {@link ActivityStream}'s values -
	 * the activity's *current* primary values (today, always living in {@link Record}) are
	 * themselves saved as a new {@code ActivitySource}/{@code ActivityStream} pair first (origin
	 * {@code FILE} - they came from this activity's own upload, not an attach), so swapping never
	 * loses data either direction. Reruns the shared post-merge recompute cascade afterward -
	 * same cascade a real #501 merge or #498 primary swap already triggers. */
	@Transactional
	public Activity setPrimarySource(String activityId, String field, String sourceId) {
		if (!"power".equals(field)) {
			throw new ValidationException("field must be 'power' - phase 1 only supports power sources.", "field");
		}
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		ActivityStream newPrimaryStream = streamRepository.findByActivityIdAndFieldAndSourceId(activityId, field, sourceId)
				.orElseThrow(() -> new NotFoundException("No such source for this activity/field."));

		List<Record> rows = recordRepository.findByActivityIdOrderByT(activityId);

		// Snapshot the activity's current primary (Record.power) as its own attached source -
		// lazily, only the first time this activity's power is ever swapped away from its
		// original file - so swapping back later is always possible.
		ActivitySource originalSource = new ActivitySource();
		originalSource.setActivity(activity);
		originalSource.setLabel(activity.getName());
		originalSource.setDevice(activity.getDevice());
		originalSource.setSensorKind(field);
		originalSource.setOrigin(ActivitySourceOrigin.FILE);
		originalSource = sourceRepository.save(originalSource);

		int t0 = rows.stream().mapToInt(Record::getT).min().orElse(0);
		int tMax = rows.stream().mapToInt(Record::getT).max().orElse(t0);
		Short[] originalValues = new Short[tMax - t0 + 1];
		Map<Integer, Integer> currentByT = rows.stream().collect(Collectors.toMap(Record::getT,
				r -> r.getPower() == null ? null : r.getPower(), (a, b) -> b));
		for (int t = t0; t <= tMax; t++) {
			Integer v = currentByT.get(t);
			if (v != null) {
				originalValues[t - t0] = v.shortValue();
			}
		}
		ActivityStream originalStream = new ActivityStream();
		originalStream.setActivity(activity);
		originalStream.setField(field);
		originalStream.setSource(originalSource);
		originalStream.setT0(t0);
		originalStream.setStreamValues(originalValues);
		originalStream.setPrimaryOf(false);
		streamRepository.save(originalStream);

		// Now write newPrimaryStream's values into Record.power.
		Short[] newValues = newPrimaryStream.getStreamValues();
		int newT0 = newPrimaryStream.getT0();
		List<Record> changedRows = new ArrayList<>();
		for (Record row : rows) {
			int index = row.getT() - newT0;
			Short value = (index >= 0 && index < newValues.length) ? newValues[index] : null;
			row.setPower(value == null ? null : value.intValue());
			changedRows.add(row);
		}
		recordRepository.saveAll(changedRows);

		activity.setBikePowerSource(classify(
				newPrimaryStream.getSource().getDevice() != null ? newPrimaryStream.getSource().getDevice() : activity.getDevice()));
		activityRepository.save(activity);

		postProcessingService.recomputeAfterMerge(activityId);

		return activityRepository.findById(activityId).orElseThrow(() -> new NotFoundException("No such activity."));
	}

	public record SourceSummary(String id, String label, String device, String sensorKind, String origin,
			Instant createdAt, boolean isPrimary) {
	}

	/** {@code isPrimary} reflects whether this source's own device classification matches the
	 * activity's *current* primary reading for its field - phase 1 only ever has "power" sources,
	 * so this is always a {@link #currentPrimarySource} comparison; it's a derived read, not a
	 * stored flag, since the primary stream itself always lives in {@link Record}, never in a
	 * {@link ActivitySource} row (see this class's own Javadoc). */
	public List<SourceSummary> listSources(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		BikePowerSource primary = currentPrimarySource(activity);
		return sourceRepository.findByActivityIdOrderByCreatedAtAsc(activityId).stream()
				.map(source -> new SourceSummary(source.getId(), source.getLabel(), source.getDevice(),
						source.getSensorKind(), source.getOrigin().wireValue(), source.getCreatedAt(),
						"power".equals(source.getSensorKind()) && classify(source.getDevice()) == primary))
				.toList();
	}
}
