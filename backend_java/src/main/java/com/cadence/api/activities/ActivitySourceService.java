package com.cadence.api.activities;

import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.AlignmentResult;
import com.cadence.api.activities.calc.ActivityMergeAlignmentCalculator.Sample;
import com.cadence.api.athletes.BikePowerSource;
import com.cadence.api.athletes.CadenceSource;
import com.cadence.api.athletes.HrSource;
import com.cadence.api.athletes.RunningPowerSource;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #503 (phases 1-3): keeps a second stream alongside an activity instead of #501's merge
 * overwriting one copy with the other - {@link #attachStream} (the {@code attach} merge {@code
 * copyMode}, called from {@link ActivityMergeService#runMerge}), {@link
 * #createParseTimeRunningPowerSource} (a run's non-preferred native/Stryd candidate, kept at
 * parse time, phase 2, power only), and {@link #setPrimarySource} (swap which stream is primary,
 * callable any time afterward). Phases 1-3 support exactly three fields - {@code power}, {@code
 * heartrate}, {@code cadence} - see {@link #VALID_FIELDS}. Mirrors backend's
 * activities/activity_source_service.py - keep the two in sync when changing either.
 */
@Service
public class ActivitySourceService {

	private static final List<String> VALID_FIELDS = List.of("power", "heartrate", "cadence");

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
	 * non-Zwift devices is a later refinement. Bike-power-only - heartrate/cadence have no
	 * device-string classification equivalent (see {@link HrSource}/{@link CadenceSource}'s own
	 * Javadoc), so those fields only ever resolve a preference match via a manually-supplied
	 * {@code sourceKind} at attach time instead. */
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

	private static void validateField(String field) {
		if (!VALID_FIELDS.contains(field)) {
			throw new ValidationException("field must be 'power', 'heartrate', or 'cadence'.", "field");
		}
	}

	/** Issue #503 (phase 3): {@code sourceKind} is optional for every field - attaching without
	 * one just means "keep a second stream, no preference match possible," never an error. When
	 * supplied, it's validated against the field's own vocabulary (e.g. rejecting {@code
	 * "trainer"} for {@code field="heartrate"}) - except for {@code power}, which ignores
	 * whatever's passed entirely and keeps using {@link #classify} instead, so passing it
	 * defensively there never 400s. */
	private static void validateSourceKind(String field, String sourceKind) {
		if (sourceKind == null || field.equals("power")) {
			return;
		}
		boolean valid = switch (field) {
			case "heartrate" -> sourceKind.equals("strap") || sourceKind.equals("wrist");
			case "cadence" -> sourceKind.equals("pedals") || sourceKind.equals("trainer") || sourceKind.equals("watch");
			default -> false;
		};
		if (!valid) {
			throw new ValidationException("source_kind '" + sourceKind + "' is not valid for field '" + field + "'.",
					"source_kind");
		}
	}

	/** Attaches {@code source}'s {@code field} stream onto {@code target} as a non-primary {@link
	 * ActivityStream}, aligned to target's own timeline using the same cross-correlation {@link
	 * ActivityMergeService#runMerge} uses for {@code overwrite} (always via cadence/power
	 * signals, regardless of which field is actually being attached - a lagging field like
	 * heartrate would be a worse synchronization signal). If the athlete's preference for {@code
	 * field} says the attached stream should be primary instead of target's current one,
	 * immediately swaps via {@link #setPrimarySource} before returning - the athlete can always
	 * swap back later. For {@code heartrate}/{@code cadence}, that preference match can only ever
	 * happen when {@code sourceKind} was supplied (there's no device-classification fallback the
	 * way {@code power} has via {@link #classify}). {@code preview=true} computes and returns the
	 * result without writing anything (no transaction-rollback trick needed here, unlike {@code
	 * overwrite} - nothing is written unless {@code preview} is false, so there's nothing to
	 * undo). */
	@Transactional
	public AttachResult attachStream(String targetId, String sourceId, String field, Integer offsetSecondsOverride,
			boolean preview, String sourceKind) {
		validateField(field);
		validateSourceKind(field, sourceKind);
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
		// t) - source's field value at target-local t is whatever the source recorded at its own
		// local time (t - offsetSeconds), same lookup direction ActivityMergeApplyService's
		// overwrite path uses.
		Map<Integer, Integer> sourceValueByT = sourceRows.stream()
				.filter(r -> ActivityMergeApplyService.getRecordFieldValue(r, field) != null)
				.collect(Collectors.toMap(Record::getT,
						r -> (Integer) ActivityMergeApplyService.getRecordFieldValue(r, field), (a, b) -> b));
		int t0 = targetRows.stream().mapToInt(Record::getT).min().orElse(0);
		int tMax = targetRows.stream().mapToInt(Record::getT).max().orElse(t0);
		Short[] values = new Short[tMax - t0 + 1];
		int copied = 0;
		int nulled = 0;
		for (int t = t0; t <= tMax; t++) {
			Integer sourceValue = sourceValueByT.get(t - offsetSeconds);
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
		activitySource.setSensorKind(field);
		activitySource.setOrigin(ActivitySourceOrigin.ATTACHED);
		activitySource.setSourceKind(sourceKind);
		activitySource = sourceRepository.save(activitySource);

		ActivityStream stream = new ActivityStream();
		stream.setActivity(target);
		stream.setField(field);
		stream.setSource(activitySource);
		stream.setT0(t0);
		stream.setStreamValues(values);
		stream.setPrimaryOf(false);
		streamRepository.save(stream);

		User athlete = target.getAthlete();
		boolean becamePrimary = false;
		if (shouldAutoSwapOnAttach(field, sourceKind, source, target, athlete)) {
			setPrimarySource(targetId, field, activitySource.getId());
			becamePrimary = true;
		}

		return new AttachResult(activitySource.getId(), offsetSeconds, alignmentScore, becamePrimary, copied, nulled);
	}

	private static boolean shouldAutoSwapOnAttach(String field, String sourceKind, Activity source, Activity target,
			User athlete) {
		return switch (field) {
			case "power" -> {
				BikePowerSource preference = athlete.getBikePowerSource();
				yield preference != BikePowerSource.ANY && classify(source.getDevice()) == preference
						&& currentPrimarySource(target) != preference;
			}
			case "heartrate" -> {
				if (sourceKind == null) {
					yield false;
				}
				HrSource preference = athlete.getHrSource();
				yield preference != HrSource.ANY && HrSource.fromWireValue(sourceKind) == preference
						&& target.getHrSource() != preference;
			}
			case "cadence" -> {
				if (sourceKind == null) {
					yield false;
				}
				CadenceSource preference = athlete.getCadenceSource();
				yield preference != CadenceSource.ANY && CadenceSource.fromWireValue(sourceKind) == preference
						&& target.getCadenceSource() != preference;
			}
			default -> false;
		};
	}

	/** Issue #503 (phase 2): stores a run's non-preferred native/Stryd candidate (the one {@code
	 * ParseFileTasklet}'s power-source resolution didn't pick) as a new, non-primary FILE-origin
	 * source/stream, so a later {@code runningPowerSource} preference change can re-designate it
	 * instead of only filtering the activity out via {@code matchesRunningPowerPreference}. No-op
	 * if the other candidate has no data at all in this file (nothing to keep) - matches {@code
	 * Activity.powerSource}'s own "blank when there's no such choice to record" convention.
	 * Called once per RUN/FIT activity right after it's persisted. Unlike {@link #attachStream},
	 * there is no alignment step - both candidates already share the activity's own single parsed
	 * timeline (same {@code t} series, same order), so {@code tSeries}/{@code otherValuesRaw} can
	 * be zipped by index directly. Power-only - there is no same-file dual-candidate case for
	 * heartrate/cadence (see {@link HrSource}/{@link CadenceSource}'s own Javadoc). */
	@Transactional
	public void createParseTimeRunningPowerSource(Activity activity, List<Integer> tSeries,
			List<Integer> otherValuesRaw, RunningPowerSource otherKind) {
		if (otherValuesRaw.stream().allMatch(Objects::isNull)) {
			return;
		}
		int t0 = tSeries.isEmpty() ? 0 : tSeries.get(0);
		int tMax = tSeries.isEmpty() ? t0 : tSeries.get(tSeries.size() - 1);
		Short[] values = new Short[tMax - t0 + 1];
		for (int i = 0; i < tSeries.size(); i++) {
			Integer v = otherValuesRaw.get(i);
			if (v != null) {
				values[tSeries.get(i) - t0] = v.shortValue();
			}
		}

		ActivitySource source = new ActivitySource();
		source.setActivity(activity);
		source.setLabel(activity.getName());
		source.setDevice(activity.getDevice());
		source.setSensorKind("power");
		source.setOrigin(ActivitySourceOrigin.FILE);
		source.setSourceKind(otherKind.wireValue());
		source = sourceRepository.save(source);

		ActivityStream stream = new ActivityStream();
		stream.setActivity(activity);
		stream.setField("power");
		stream.setSource(source);
		stream.setT0(t0);
		stream.setStreamValues(values);
		stream.setPrimaryOf(false);
		streamRepository.save(stream);
	}

	/** Swaps {@code field} on {@code activityId} with the named {@link ActivityStream}'s values -
	 * the activity's *current* primary values (today, always living in {@link Record}) are
	 * themselves saved as a new {@code ActivitySource}/{@code ActivityStream} pair first (origin
	 * {@code FILE} - they came from this activity's own upload, not an attach), so swapping never
	 * loses data either direction. Reruns the shared post-merge recompute cascade afterward -
	 * same cascade a real #501 merge or #498 primary swap already triggers. */
	@Transactional
	public Activity setPrimarySource(String activityId, String field, String sourceId) {
		Activity activity = swapPrimaryStream(activityId, field, sourceId);
		postProcessingService.recomputeAfterMerge(activityId);
		return activityRepository.findById(activityId).orElseThrow(() -> new NotFoundException("No such activity."));
	}

	/** The data-only half of {@link #setPrimarySource} - everything except the recompute
	 * cascade. Package-visible: besides {@code setPrimarySource} itself, only {@code
	 * RunningPowerSourceRecomputeService}'s bulk loop calls this directly, deferring the
	 * athlete-wide cascade ({@code ActivityMergePostProcessingService#recomputeAfterMerge}'s own
	 * full-ledger rebuild) to run once per athlete instead of once per swapped activity, which
	 * would otherwise be quadratic in the athlete's activity count. */
	@Transactional
	Activity swapPrimaryStream(String activityId, String field, String sourceId) {
		validateField(field);
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		ActivityStream newPrimaryStream = streamRepository.findByActivityIdAndFieldAndSourceId(activityId, field, sourceId)
				.orElseThrow(() -> new NotFoundException("No such source for this activity/field."));

		List<Record> rows = recordRepository.findByActivityIdOrderByT(activityId);

		// Snapshot the activity's current primary as its own attached source - unconditionally,
		// so swapping back later is always possible.
		ActivitySource originalSource = new ActivitySource();
		originalSource.setActivity(activity);
		originalSource.setLabel(activity.getName());
		originalSource.setDevice(activity.getDevice());
		originalSource.setSensorKind(field);
		originalSource.setOrigin(ActivitySourceOrigin.FILE);
		originalSource.setSourceKind(currentSourceKindForSnapshot(activity, field));
		originalSource = sourceRepository.save(originalSource);

		int t0 = rows.stream().mapToInt(Record::getT).min().orElse(0);
		int tMax = rows.stream().mapToInt(Record::getT).max().orElse(t0);
		Short[] originalValues = new Short[tMax - t0 + 1];
		Map<Integer, Integer> currentByT = rows.stream().collect(Collectors.toMap(Record::getT,
				r -> (Integer) ActivityMergeApplyService.getRecordFieldValue(r, field), (a, b) -> b));
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

		// Now write newPrimaryStream's values into Record.<field>.
		Short[] newValues = newPrimaryStream.getStreamValues();
		int newT0 = newPrimaryStream.getT0();
		List<Record> changedRows = new ArrayList<>();
		for (Record row : rows) {
			int index = row.getT() - newT0;
			Short value = (index >= 0 && index < newValues.length) ? newValues[index] : null;
			ActivityMergeApplyService.setRecordFieldValue(row, field, value == null ? null : value.intValue());
			changedRows.add(row);
		}
		recordRepository.saveAll(changedRows);

		stampActivityAfterSwap(activity, field, newPrimaryStream.getSource());
		activityRepository.save(activity);

		return activityRepository.findById(activityId).orElseThrow(() -> new NotFoundException("No such activity."));
	}

	/** What to tag the activity's *current* primary with before it's swapped away - power+run
	 * reads {@code activity.getPowerSource()} (the only field with a parse-time-resolved value to
	 * fall back on); heartrate/cadence read the activity's own {@code hrSource}/{@code
	 * cadenceSource} (null until a previous manual swap tagged one, in which case this snapshot
	 * stays untagged too - there's nothing to infer it from). Bike power is deliberately excluded
	 * here - it keeps tagging via {@link #classify} on {@code device} at read time instead (see
	 * {@link #isPrimaryForField}), never via a stored sourceKind. */
	private static String currentSourceKindForSnapshot(Activity activity, String field) {
		return switch (field) {
			case "power" -> activity.getSport() == Sport.RUN && activity.getPowerSource() != null
					? activity.getPowerSource().wireValue() : null;
			case "heartrate" -> activity.getHrSource() != null ? activity.getHrSource().wireValue() : null;
			case "cadence" -> activity.getCadenceSource() != null ? activity.getCadenceSource().wireValue() : null;
			default -> null;
		};
	}

	/** Stamps the activity-level resolved-source field for whichever stream just became primary -
	 * never clears an existing value when the new source was never tagged (a swap to an untagged
	 * stream just leaves the activity's own field as it was). */
	private static void stampActivityAfterSwap(Activity activity, String field, ActivitySource newSource) {
		switch (field) {
			case "power" -> {
				if (activity.getSport() == Sport.BIKE) {
					activity.setBikePowerSource(
							classify(newSource.getDevice() != null ? newSource.getDevice() : activity.getDevice()));
				}
				else if (activity.getSport() == Sport.RUN && newSource.getSourceKind() != null) {
					activity.setPowerSource(RunningPowerSource.fromWireValue(newSource.getSourceKind()));
				}
			}
			case "heartrate" -> {
				if (newSource.getSourceKind() != null) {
					activity.setHrSource(HrSource.fromWireValue(newSource.getSourceKind()));
				}
			}
			case "cadence" -> {
				if (newSource.getSourceKind() != null) {
					activity.setCadenceSource(CadenceSource.fromWireValue(newSource.getSourceKind()));
				}
			}
			default -> {
			}
		}
	}

	public record SourceSummary(String id, String label, String device, String sensorKind, String origin,
			Instant createdAt, boolean isPrimary) {
	}

	/** {@code isPrimary} reflects whether this source matches the activity's *current* primary
	 * reading for its field - power/bike by device classification ({@link #currentPrimarySource}),
	 * power/run and heartrate/cadence by comparing the source's own {@code sourceKind} against
	 * the matching {@code Activity} field. It's a derived read, not a stored flag, since the
	 * primary stream itself always lives in {@link Record}, never in a {@link ActivitySource} row
	 * (see this class's own Javadoc). */
	public List<SourceSummary> listSources(String activityId) {
		Activity activity = activityRepository.findById(activityId)
				.orElseThrow(() -> new NotFoundException("No such activity."));
		return sourceRepository.findByActivityIdOrderByCreatedAtAsc(activityId).stream()
				.map(source -> new SourceSummary(source.getId(), source.getLabel(), source.getDevice(),
						source.getSensorKind(), source.getOrigin().wireValue(), source.getCreatedAt(),
						isPrimaryForField(source, activity)))
				.toList();
	}

	private static boolean isPrimaryForField(ActivitySource source, Activity activity) {
		return switch (source.getSensorKind()) {
			case "power" -> activity.getSport() == Sport.RUN
					? source.getSourceKind() != null
							&& RunningPowerSource.fromWireValue(source.getSourceKind()) == activity.getPowerSource()
					: classify(source.getDevice()) == currentPrimarySource(activity);
			case "heartrate" -> source.getSourceKind() != null
					&& HrSource.fromWireValue(source.getSourceKind()) == activity.getHrSource();
			case "cadence" -> source.getSourceKind() != null
					&& CadenceSource.fromWireValue(source.getSourceKind()) == activity.getCadenceSource();
			default -> false;
		};
	}
}
