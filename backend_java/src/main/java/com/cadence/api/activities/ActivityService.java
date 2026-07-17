package com.cadence.api.activities;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.cql.CqlException;
import com.cadence.api.cql.CqlNode;
import com.cadence.api.cql.CqlParser;
import com.cadence.api.cql.spec.CqlSpecification;
import com.cadence.api.workouts.WorkoutService;
import java.util.List;
import java.util.Map;
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
	private final ActivityFieldMap fieldMap = new ActivityFieldMap();

	public ActivityService(ActivityRepository activityRepository, ActivityTagRepository activityTagRepository,
			RecordRepository recordRepository, ActivityCursorPagination pagination, WorkoutService workoutService) {
		this.activityRepository = activityRepository;
		this.activityTagRepository = activityTagRepository;
		this.recordRepository = recordRepository;
		this.pagination = pagination;
		this.workoutService = workoutService;
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
				activity.getMovingTime(), activity.getDistanceKm(), activity.getDistanceSource(),
				activity.getAvgPower(), activity.getNormPower(), activity.getIntensity(), activity.getTss(),
				activity.getAvgHr(), activity.getMaxHr(), activity.getAscent(),
				activity.getStartWeightKg(), activity.getEndWeightKg(), activity.getFluidsMl(),
				activity.getAvgAirTemp(), activity.getAvgHumidity(),
				activity.getAerobicTrainingEffect(), activity.getAnaerobicTrainingEffect(), activity.getTrainingEffectLabel(),
				tags,
				activity.getWorkout() != null ? activity.getWorkout().getId() : null,
				activity.getBike() != null ? activity.getBike().getId() : null,
				activity.getShoe() != null ? activity.getShoe().getId() : null,
				activity.getParentActivity() != null ? activity.getParentActivity().getId() : null,
				childIds,
				activity.getPrimaryActivity() != null ? activity.getPrimaryActivity().getId() : null,
				duplicateIds);
	}

	public CursorPage<ActivityResponse> list(String athleteId, String q, Sport sportFilter, Environment environmentFilter,
			String cursor, int limit) {
		// Multisport children are reachable via their parent's child_activity_ids, not the list -
		// showing legs alongside the parent would present the same session twice. Duplicate
		// recordings are likewise reachable only via their primary's duplicate_activity_ids.
		Specification<Activity> spec = (root, query, cb) -> cb.and(
				cb.equal(root.get("athlete").get("id"), athleteId),
				cb.isNull(root.get("parentActivity")),
				cb.isNull(root.get("primaryActivity")));
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
		if (body.containsKey("name")) {
			activity.setName((String) body.get("name"));
		}
		if (body.containsKey("sport")) {
			activity.setSport(Sport.fromWireValue((String) body.get("sport")));
		}
		if (body.containsKey("workout_id")) {
			Object value = body.get("workout_id");
			activity.setWorkout(value == null ? null : workoutService.getWorkout((String) value));
		}
		if (body.containsKey("primary_activity_id")) {
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
		return activityRepository.save(activity);
	}

	@Transactional
	public void deleteActivity(String id) {
		activityRepository.deleteById(id);
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

	private boolean isMultisportLinked(Activity activity) {
		return activity.getSport() == Sport.MULTISPORT || activity.getSport() == Sport.TRANSITION
				|| activity.getParentActivity() != null;
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
}
