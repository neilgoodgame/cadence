package com.cadence.api.activities;

import com.cadence.api.activities.calc.TrainingEffectLabel;
import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.activities.dto.ActivityThresholdHistoryEntry;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryKind;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.athletes.ThresholdHistoryService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.cql.CqlException;
import com.cadence.api.cql.CqlNode;
import com.cadence.api.cql.CqlParser;
import com.cadence.api.cql.spec.CqlSpecification;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.GearMileageService;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.users.User;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

	private final ActivityRepository activityRepository;
	private final ActivityTagRepository activityTagRepository;
	private final RecordRepository recordRepository;
	private final ActivityCursorPagination pagination;
	private final WorkoutService workoutService;
	private final ThresholdHistoryRepository thresholdHistoryRepository;
	private final WorkoutMatchPreferenceService matchPreferenceService;
	private final ActivityDurabilityRepository durabilityRepository;
	private final BikeRepository bikeRepository;
	private final ShoeRepository shoeRepository;
	private final GearMileageService gearMileageService;
	private final ThresholdHistoryService thresholdHistoryService;
	private final TssRecomputeService tssRecomputeService;
	private final ActivityFieldMap fieldMap = new ActivityFieldMap();

	public ActivityService(ActivityRepository activityRepository, ActivityTagRepository activityTagRepository,
			RecordRepository recordRepository, ActivityCursorPagination pagination, WorkoutService workoutService,
			ThresholdHistoryRepository thresholdHistoryRepository, WorkoutMatchPreferenceService matchPreferenceService,
			ActivityDurabilityRepository durabilityRepository, BikeRepository bikeRepository, ShoeRepository shoeRepository,
			GearMileageService gearMileageService, ThresholdHistoryService thresholdHistoryService,
			TssRecomputeService tssRecomputeService) {
		this.activityRepository = activityRepository;
		this.activityTagRepository = activityTagRepository;
		this.recordRepository = recordRepository;
		this.pagination = pagination;
		this.workoutService = workoutService;
		this.thresholdHistoryRepository = thresholdHistoryRepository;
		this.matchPreferenceService = matchPreferenceService;
		this.durabilityRepository = durabilityRepository;
		this.bikeRepository = bikeRepository;
		this.shoeRepository = shoeRepository;
		this.gearMileageService = gearMileageService;
		this.thresholdHistoryService = thresholdHistoryService;
		this.tssRecomputeService = tssRecomputeService;
	}

	public Activity getActivity(String id) {
		return activityRepository.findById(id).orElseThrow(() -> new NotFoundException("No such activity."));
	}

	public ActivityResponse toResponse(Activity activity) {
		List<String> tags = activityTagRepository.findTagNamesByActivityId(activity.getId());
		List<String> childIds = activity.getSport() == Sport.MULTISPORT
				? activityRepository.findChildIds(activity.getId())
				: List.of();
		// A duplicate can never itself be a primary (chains are rejected on update),
		// so skip the lookup for anything already linked to a primary.
		List<String> duplicateIds = activity.getPrimaryActivity() == null
				? activityRepository.findDuplicateIds(activity.getId())
				: List.of();
		return new ActivityResponse(
				activity.getId(), activity.getAthlete().getId(), activity.getSport(), activity.getEnvironment(),
				activity.isHasGps(), activity.getName(), activity.getStartDate(), activity.getSource(),
				activity.getDevice(), activity.getMovingTime(), activity.getDistanceKm(), activity.getDistanceSource(),
				activity.getPowerSource(),
				activity.getAvgPower(), activity.getNormPower(), activity.getIntensity(), activity.getTss(),
				activity.getAvgHr(), activity.getMaxHr(), activity.getAscent(),
				activity.getMaxPower(), activity.getAvgCadence(), activity.getMaxCadence(), activity.getMaxSpeed(),
				activity.getTotalDescent(), activity.getElevationMin(), activity.getElevationMax(),
				activity.getCalories(), activity.getTrimp(), activity.getAvgLeftBalancePct(),
				thresholdHistoryFor(activity),
				activity.getStartWeightKg(), activity.getEndWeightKg(), activity.getFluidsMl(),
				activity.getAvgAirTemp(), activity.getAvgHumidity(),
				activity.getAvgHeatStrain(), activity.getMaxHeatStrain(), activity.getAvgCoreTemp(),
				activity.getMaxCoreTemp(), activity.getAvgSkinTemp(), activity.getMaxSkinTemp(),
				activity.getAerobicTrainingEffect(), activity.getAnaerobicTrainingEffect(), activity.getTrainingEffectLabel(),
				activity.getDecouplingPct(), activity.getEfFirst(), activity.getEfSecond(), activity.getSteadySeconds(),
				activity.isDecouplingQualified(), activity.getDecouplingReasons(), activity.getDecouplingVi(),
				activity.getDecouplingIf(), activity.getDecouplingAvgTemp(), activity.getDecouplingAvgCore(),
				activity.getDecouplingAvgSkin(), activity.isDecouplingWarm(), activity.isDecouplingHot(),
				activity.getDecouplingHrCoveragePct(), activity.getDecouplingPowerCoveragePct(),
				activity.getDecouplingHalves(), durabilityFor(activity),
				tags,
				activity.getWorkout() != null ? activity.getWorkout().getId() : null,
				activity.getBike() != null ? activity.getBike().getId() : null,
				activity.getShoe() != null ? activity.getShoe().getId() : null,
				activity.getParentActivity() != null ? activity.getParentActivity().getId() : null,
				childIds,
				activity.getPrimaryActivity() != null ? activity.getPrimaryActivity().getId() : null,
				duplicateIds);
	}

	/** Two distinct signals, both keyed off this activity - empty for the vast majority of
	 * activities:
	 * <ul>
	 * <li>Rows this activity's own effort produced (sourceActivity == activity) - "is this
	 * activity itself a new PR."
	 * <li>Rows whose currentFrom == this activity's own date but whose source is a DIFFERENT,
	 * earlier activity - this activity's own ingest/recompute pass is what noticed an earlier,
	 * dormant effort had become current, once its window rival aged out (see
	 * ThresholdHistory.getCurrentFrom()'s Javadoc - a confirmed real scenario, not hypothetical:
	 * importing today's run can be the trigger that reveals a months-old effort just took over).
	 * </ul>
	 * Distinguished via sourceActivityId on the returned DTO - equal to this activity's own id
	 * for the first case, a different activity's id for the second. isCurrent is true when a row
	 * is still the latest entry for its field (not yet superseded by a later, more recent
	 * effort). */
	private List<com.cadence.api.activities.dto.ActivityDurabilityResponse> durabilityFor(Activity activity) {
		return durabilityRepository.findByActivityIdOrderByThresholdAscWindowSAsc(activity.getId()).stream()
				.map(d -> new com.cadence.api.activities.dto.ActivityDurabilityResponse(
						d.getThreshold(), d.getWindowS(), d.getPower(), d.getStartOffsetS()))
				.toList();
	}

	private List<ActivityThresholdHistoryEntry> thresholdHistoryFor(Activity activity) {
		List<ThresholdHistory> ownEffort = thresholdHistoryRepository.findBySourceActivityId(activity.getId());
		LocalDate activityDate = activity.getStartDate().atZone(ZoneOffset.UTC).toLocalDate();
		List<ThresholdHistory> revealed = thresholdHistoryRepository
				.findByAthleteIdAndCurrentFrom(activity.getAthlete().getId(), activityDate).stream()
				.filter(entry -> !activity.getId().equals(sourceActivityIdOf(entry)))
				// A decayed row (issue #480's decay core, #485) becomes current with no source
				// activity at all, same as a manual edit - it isn't "revealed" by this activity either.
				.filter(entry -> entry.getKind() != ThresholdHistoryKind.DECAYED)
				// Nor is a correction-only row (issue #493) - its currentFrom matching this
				// activity's date is a backdating coincidence, not something this activity's own
				// ingest/recompute pass actually noticed.
				.filter(entry -> !entry.isCorrectionOnly())
				.toList();
		return Stream.concat(ownEffort.stream(), revealed.stream())
				.map(entry -> {
					ThresholdHistory latest = thresholdHistoryRepository
							.findFirstByAthleteIdAndFieldAndCorrectionOnlyFalseOrderByEffectiveFromDescIdDesc(
									activity.getAthlete().getId(), entry.getField())
							.orElse(null);
					boolean isCurrent = latest != null && latest.getId().equals(entry.getId());
					Object value = entry.getField() == ThresholdField.THRESHOLD_PACE ? entry.getValuePace() : entry.getValueNumeric();
					return new ActivityThresholdHistoryEntry(entry.getField(), value, isCurrent, sourceActivityIdOf(entry));
				})
				.toList();
	}

	// Null for a manually-entered value - not sourced from any activity.
	private static String sourceActivityIdOf(ThresholdHistory entry) {
		return entry.getSourceActivity() != null ? entry.getSourceActivity().getId() : null;
	}

	public CursorPage<ActivityResponse> list(String athleteId, String q, Sport sportFilter, Environment environmentFilter,
			String tagFilter, LocalDate after, LocalDate before, String cursor, int limit,
			String bikeIdFilter, String shoeIdFilter) {
		return list(athleteId, q, sportFilter, environmentFilter, tagFilter, after, before, cursor, limit, bikeIdFilter,
				shoeIdFilter, false);
	}

	/** As {@link #list(String, String, Sport, Environment, String, LocalDate, LocalDate, String,
	 * int, String, String)}, but {@code includeDuplicates} (issue #498) can surface same-ride-
	 * twice duplicate copies that are otherwise hidden entirely - only the MCP list_activities
	 * tool exposes this; the REST endpoint always leaves it at the default. */
	public CursorPage<ActivityResponse> list(String athleteId, String q, Sport sportFilter, Environment environmentFilter,
			String tagFilter, LocalDate after, LocalDate before, String cursor, int limit,
			String bikeIdFilter, String shoeIdFilter, boolean includeDuplicates) {
		// Multisport children are reachable via their parent's child_activity_ids, not the list -
		// showing legs alongside the parent would present the same session twice. Duplicate
		// recordings are likewise reachable only via their primary's duplicate_activity_ids,
		// unless includeDuplicates overrides that.
		Specification<Activity> spec = (root, query, cb) -> cb.and(
				cb.equal(root.get("athlete").get("id"), athleteId),
				cb.isNull(root.get("parentActivity")));
		if (!includeDuplicates) {
			spec = spec.and((root, query, cb) -> cb.isNull(root.get("primaryActivity")));
		}
		Sort.Order primaryOrder = Sort.Order.desc("startDate");

		if (q != null && !q.isBlank()) {
			CqlParser.ParseResult result = CqlParser.parse(q);
			if (!result.empty()) {
				CqlNode ast = result.ast();
				if (ast != null) {
					spec = spec.and(CqlSpecification.compile(ast, fieldMap, new ActivityTagPredicateFactory()));
				}
				if (result.order() != null) {
					String ormField = fieldMap.resolve(result.order().field());
					if (ormField == null) {
						throw new CqlException("Field \"" + result.order().field() + "\" is not sortable here", "q");
					}
					primaryOrder = "desc".equals(result.order().direction()) ? Sort.Order.desc(ormField) : Sort.Order.asc(ormField);
				}
			}
		}
		if (sportFilter != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("sport"), sportFilter));
		}
		if (environmentFilter != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("environment"), environmentFilter));
		}
		// Separate from q: the CQL "tag <name>" clause only ever captures a single token as
		// the value (see CqlGrammarParser), so a multi-word tag name like "Heat Training"
		// silently loses everything after the first word and matches nothing. The tag filter
		// chips drive this param directly instead of smuggling the tag name through CQL text.
		if (tagFilter != null && !tagFilter.isBlank()) {
			spec = spec.and((root, query, cb) -> new ActivityTagPredicateFactory().forTagName(root, query, cb, tagFilter));
		}
		// The gear screen's "activities using this bike/shoe" list - already implicitly scoped
		// to this athlete by the athlete.id predicate above, since bike/shoe on an activity can
		// only ever point at that same athlete's own gear (enforced at link time).
		if (bikeIdFilter != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("bike").get("id"), bikeIdFilter));
		}
		if (shoeIdFilter != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("shoe").get("id"), shoeIdFilter));
		}
		if (after != null) {
			Instant afterInstant = after.atStartOfDay(ZoneOffset.UTC).toInstant();
			spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startDate"), afterInstant));
		}
		if (before != null) {
			// Add one day so 'before' is inclusive of the named date.
			Instant beforeInstant = before.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			spec = spec.and((root, query, cb) -> cb.lessThan(root.get("startDate"), beforeInstant));
		}

		CursorPage<Activity> page = pagination.page(spec, primaryOrder, cursor, limit);
		return new CursorPage<>(page.hasMore(), page.nextCursor(), page.data().stream().map(this::toResponse).toList());
	}

	/**
	 * Takes the raw request map rather than a typed DTO: the contract distinguishes "field
	 * absent" (leave unchanged) from "field explicitly null" (clear), e.g. {@code workout_id}
	 * unlinks a workout when sent as {@code null} - a plain record can't tell those apart after
	 * Jackson deserialization, since both end up as a null Java field.
	 */
	@Transactional
	public Activity updateActivity(Activity activity, Map<String, Object> body) {
		// Re-fetch within this method's own transaction: the controller's own (non-transactional)
		// lookup already closed its session by the time this method runs, so the passed-in
		// `activity`'s lazy associations (athlete, workout) are bound to a dead session. Reading
		// just their id (e.g. the previous workout_id check below) works on a detached proxy, but
		// fully initializing one - as applyRename/applySideEffects do for `activity.getAthlete()`
		// once a new match is applied - throws LazyInitializationException without this.
		activity = activityRepository.findById(activity.getId())
				.orElseThrow(() -> new NotFoundException("No such activity."));
		if (body.containsKey("name")) {
			activity.setName((String) body.get("name"));
		}
		if (body.containsKey("sport")) {
			activity.setSport(Sport.fromWireValue((String) body.get("sport")));
		}
		Workout newlyMatchedWorkout = null;
		if (body.containsKey("workout_id")) {
			Object value = body.get("workout_id");
			String previousWorkoutId = activity.getWorkout() == null ? null : activity.getWorkout().getId();
			if (value == null) {
				activity.setWorkout(null);
			}
			else {
				Workout workout = workoutService.getWorkout((String) value);
				activity.setWorkout(workout);
				// Accepting a Scan-for-matches candidate is this same update, just with only
				// workout_id in the body - a genuinely new match applies the athlete's match
				// preferences (rename/copy-tags/regenerate-laps) exactly like ingest-time
				// auto-match already does (see WorkoutMatchPreferenceService). Re-submitting the
				// same workout_id is a no-op, not a fresh match.
				if (!value.equals(previousWorkoutId)) {
					newlyMatchedWorkout = workout;
				}
			}
		}
		if (newlyMatchedWorkout != null) {
			matchPreferenceService.applyRename(activity, newlyMatchedWorkout, activity.getAthlete(),
					body.containsKey("name"));
		}
		String oldBikeId = null;
		boolean bikeChanged = body.containsKey("bike_id");
		if (bikeChanged) {
			Object value = body.get("bike_id");
			oldBikeId = activity.getBike() == null ? null : activity.getBike().getId();
			activity.setBike(value == null ? null : resolveBike(activity.getAthlete().getId(), (String) value));
		}
		String oldShoeId = null;
		boolean shoeChanged = body.containsKey("shoe_id");
		if (shoeChanged) {
			Object value = body.get("shoe_id");
			oldShoeId = activity.getShoe() == null ? null : activity.getShoe().getId();
			activity.setShoe(value == null ? null : resolveShoe(activity.getAthlete().getId(), (String) value));
		}
		boolean primaryActivityChanged = body.containsKey("primary_activity_id");
		boolean wasDuplicate = activity.getPrimaryActivity() != null;
		if (primaryActivityChanged) {
			Object value = body.get("primary_activity_id");
			activity.setPrimaryActivity(value == null ? null : resolvePrimaryActivity(activity, (String) value));
		}
		if (body.containsKey("start_weight_kg")) {
			activity.setStartWeightKg(toDouble(body.get("start_weight_kg")));
		}
		if (body.containsKey("end_weight_kg")) {
			activity.setEndWeightKg(toDouble(body.get("end_weight_kg")));
		}
		if (body.containsKey("fluids_ml")) {
			activity.setFluidsMl(toInteger(body.get("fluids_ml")));
		}
		if (body.containsKey("avg_air_temp") || body.containsKey("avg_humidity")) {
			boolean strydComputed = activity.getSport() == Sport.RUN
					&& recordRepository.existsByIdActivityIdAndAirTempIsNotNull(activity.getId());
			if (!strydComputed) {
				if (body.containsKey("avg_air_temp")) {
					activity.setAvgAirTemp(toDouble(body.get("avg_air_temp")));
				}
				if (body.containsKey("avg_humidity")) {
					activity.setAvgHumidity(toInteger(body.get("avg_humidity")));
				}
			}
		}
		// Unlike avg_air_temp/avg_humidity above, there's no "real device data" to protect - a
		// FIT-embedded total_training_effect is Garmin-device-only to begin with, and a
		// Zwift-originated file routed through Garmin Connect often carries a meaningless 0.0
		// placeholder rather than Connect's own (better) cloud-computed value, which never gets
		// written back into the downloadable FIT at all. So this is always directly
		// user-editable, not gated behind "only if currently null".
		if (body.containsKey("aerobic_training_effect")) {
			Double aerobic = toTrainingEffect(body.get("aerobic_training_effect"));
			activity.setAerobicTrainingEffect(aerobic);
			activity.setTrainingEffectLabel(TrainingEffectLabel.of(aerobic));
		}
		if (body.containsKey("anaerobic_training_effect")) {
			activity.setAnaerobicTrainingEffect(toTrainingEffect(body.get("anaerobic_training_effect")));
		}
		Activity saved = activityRepository.save(activity);
		if (bikeChanged) {
			String newBikeId = saved.getBike() == null ? null : saved.getBike().getId();
			gearMileageService.applyBikeChange(oldBikeId, newBikeId, saved.getDistanceKm());
		}
		if (shoeChanged) {
			String newShoeId = saved.getShoe() == null ? null : saved.getShoe().getId();
			gearMileageService.applyShoeChange(oldShoeId, newShoeId, saved.getDistanceKm());
		}
		if (primaryActivityChanged) {
			applyDuplicateGearMileageChange(saved, wasDuplicate, saved.getPrimaryActivity() != null);
		}
		if (newlyMatchedWorkout != null) {
			matchPreferenceService.applySideEffects(saved, newlyMatchedWorkout, saved.getAthlete());
		}
		return saved;
	}

	@Transactional
	public void deleteActivity(String id) {
		Activity activity = activityRepository.findById(id).orElseThrow(() -> new NotFoundException("No such activity."));
		String bikeId = activity.getBike() == null ? null : activity.getBike().getId();
		String shoeId = activity.getShoe() == null ? null : activity.getShoe().getId();
		gearMileageService.applyBikeChange(bikeId, null, activity.getDistanceKm());
		gearMileageService.applyShoeChange(shoeId, null, activity.getDistanceKm());
		activityRepository.deleteById(id);
	}

	@Transactional
	public void deleteAllActivities(String athleteId) {
		Map<String, Integer> bikeTotals = new HashMap<>();
		for (Object[] row : activityRepository.findBikeIdAndDistanceKmByAthleteId(athleteId)) {
			bikeTotals.merge((String) row[0], Math.round((float) (double) row[1]), Integer::sum);
		}
		Map<String, Integer> shoeTotals = new HashMap<>();
		for (Object[] row : activityRepository.findShoeIdAndDistanceKmByAthleteId(athleteId)) {
			shoeTotals.merge((String) row[0], Math.round((float) (double) row[1]), Integer::sum);
		}
		bikeTotals.forEach((bikeId, total) -> gearMileageService.applyBikeChange(bikeId, null, total));
		shoeTotals.forEach((shoeId, total) -> gearMileageService.applyShoeChange(shoeId, null, total));
		recordRepository.deleteByAthleteId(athleteId);
		activityRepository.deleteAllByAthleteId(athleteId);
	}

	/**
	 * Validates marking {@code activity} as a duplicate of {@code primaryId}: same athlete,
	 * no self-links, no chains (neither side may already be a duplicate or have duplicates
	 * pointing the other way), and multisport parents/legs stay out entirely - their
	 * training load is already handled by the parent/child relationship.
	 */
	private Activity resolvePrimaryActivity(Activity activity, String primaryId) {
		if (primaryId.equals(activity.getId())) {
			throw new ValidationException("An activity cannot be a duplicate of itself.");
		}
		Activity primary = getActivity(primaryId);
		if (!primary.getAthlete().getId().equals(activity.getAthlete().getId())) {
			throw new ValidationException("The primary activity must belong to the same athlete.");
		}
		if (primary.getPrimaryActivity() != null) {
			throw new ValidationException("The chosen primary is itself a duplicate of another activity.");
		}
		if (activityRepository.existsByPrimaryActivityId(activity.getId())) {
			throw new ValidationException("This activity has duplicates of its own; re-link those first.");
		}
		if (isMultisportLinked(activity) || isMultisportLinked(primary)) {
			throw new ValidationException("Multisport sessions and their legs cannot be linked as duplicates.");
		}
		return primary;
	}

	/** Issue #498's MCP write-tool equivalent of {@code PATCH .../primary_activity_id} - reuses
	 * {@link #resolvePrimaryActivity}'s exact validation, then runs the same post-resolution
	 * recompute the overlap backfill command uses: a manual link can retroactively disqualify a
	 * duplicate-sourced best effort from the threshold ledger just like an automatic one does, so
	 * {@link #recomputeAfterDuplicateChange} rebuilds it and re-rates TSS/intensity too. */
	@Transactional
	public Activity markDuplicate(String activityId, String primaryId) {
		Activity activity = getActivity(activityId);
		Activity primary = resolvePrimaryActivity(activity, primaryId);
		activity.setPrimaryActivity(primary);
		Activity saved = activityRepository.save(activity);
		applyDuplicateGearMileageChange(saved, false, true);
		recomputeAfterDuplicateChange(activity.getAthlete());
		return saved;
	}

	/** Reverses {@link #markDuplicate} - a no-op (not an error) if {@code activityId} isn't
	 * currently a duplicate of anything. */
	@Transactional
	public Activity unmarkDuplicate(String activityId) {
		Activity activity = getActivity(activityId);
		if (activity.getPrimaryActivity() == null) {
			return activity;
		}
		activity.setPrimaryActivity(null);
		Activity saved = activityRepository.save(activity);
		applyDuplicateGearMileageChange(saved, true, false);
		recomputeAfterDuplicateChange(activity.getAthlete());
		return saved;
	}

	/** Swaps which copy of a group is primary: {@code activityId} - currently a duplicate -
	 * becomes the primary, and the old primary becomes its duplicate instead. Any *other*
	 * sibling still pointing at the old primary is re-pointed at the new one too, rather than
	 * left pointing at an activity that's now itself a duplicate - the same chain-prevention
	 * invariant {@link #resolvePrimaryActivity} enforces one link at a time. */
	@Transactional
	public Activity setPrimary(String activityId) {
		Activity activity = getActivity(activityId);
		Activity oldPrimary = activity.getPrimaryActivity();
		if (oldPrimary == null) {
			throw new ValidationException("Activity is already the primary; nothing to swap.");
		}
		for (Activity sibling : activityRepository.findByPrimaryActivityId(oldPrimary.getId())) {
			if (!sibling.getId().equals(activity.getId())) {
				sibling.setPrimaryActivity(activity);
				activityRepository.save(sibling);
			}
		}
		oldPrimary.setPrimaryActivity(activity);
		Activity savedOldPrimary = activityRepository.save(oldPrimary);
		activity.setPrimaryActivity(null);
		Activity saved = activityRepository.save(activity);
		applyDuplicateGearMileageChange(savedOldPrimary, false, true);
		applyDuplicateGearMileageChange(saved, true, false);
		recomputeAfterDuplicateChange(activity.getAthlete());
		return saved;
	}

	/** Issue #498: shared by markDuplicate/unmarkDuplicate/setPrimary and updateActivity's own
	 * primary_activity_id branch - a duplicate-marked activity's distance must not double-count
	 * on its bike/shoe's mileage total alongside its primary's. */
	private void applyDuplicateGearMileageChange(Activity activity, boolean wasDuplicate, boolean isDuplicate) {
		String bikeId = activity.getBike() == null ? null : activity.getBike().getId();
		String shoeId = activity.getShoe() == null ? null : activity.getShoe().getId();
		gearMileageService.applyDuplicateChange(bikeId, shoeId, activity.getDistanceKm(), wasDuplicate, isDuplicate);
	}

	/** Shared by markDuplicate/unmarkDuplicate/setPrimary, DuplicateResolutionBatchService's
	 * resolveDuplicatesBatch/undoBatch, and the uploads.batch package's own ingest-time and
	 * batch-finalize auto-mark paths (ParseFileTasklet, UploadBatchFinalizer) - same cascade
	 * OverlapBackfillRunner's apply mode runs per affected athlete: rebuild every threshold
	 * field's ledger (duplicate-sourced best efforts are no longer, or newly are, eligible
	 * candidates), then re-rate TSS/intensity against the rebuilt ledger. The fitness trend reads
	 * Activity.tss live and already excludes duplicates, so there's no separate series to
	 * rebuild. Public rather than package-visible since uploads.batch is a different package. */
	public void recomputeAfterDuplicateChange(User athlete) {
		for (ThresholdField field : ThresholdField.values()) {
			thresholdHistoryService.rebuildHistory(athlete, field, (current, total) -> { });
		}
		tssRecomputeService.recomputeForAthlete(athlete);
	}

	private boolean isMultisportLinked(Activity activity) {
		return activity.getSport() == Sport.MULTISPORT || activity.getSport() == Sport.TRANSITION
				|| activity.getParentActivity() != null;
	}

	private Bike resolveBike(String athleteId, String bikeId) {
		return bikeRepository.findByIdAndAthleteId(bikeId, athleteId)
				.orElseThrow(() -> new ValidationException("Unknown bike '" + bikeId + "'.", "bike_id"));
	}

	private Shoe resolveShoe(String athleteId, String shoeId) {
		return shoeRepository.findByIdAndAthleteId(shoeId, athleteId)
				.orElseThrow(() -> new ValidationException("Unknown shoe '" + shoeId + "'.", "shoe_id"));
	}

	private Double toDouble(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		throw new ValidationException("Expected a number.");
	}

	private Integer toInteger(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number n) {
			return n.intValue();
		}
		throw new ValidationException("Expected a number.");
	}

	private Double toTrainingEffect(Object value) {
		Double parsed = toDouble(value);
		if (parsed != null && (parsed < 0.0 || parsed > 5.0)) {
			throw new ValidationException("Training effect must be between 0.0 and 5.0.");
		}
		return parsed;
	}
}
