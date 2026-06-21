package com.cadence.api.activities;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.common.domain.Sport;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seek (keyset) pagination for activities: fetches {@code limit + 1} rows so the extra row's
 * presence becomes {@code has_more} without a separate {@code COUNT(*)}, and encodes the cursor
 * as the active sort field's value at the last row plus its id as a tiebreaker. Cursor-based
 * pagination is per-contract for {@code /v1/activities} only - every other list endpoint in this
 * API is unpaginated, so this lives here rather than as shared infrastructure.
 */
@Component
public class ActivityCursorPagination {

	private final ActivityRepository activityRepository;
	private final JsonMapper jsonMapper;

	public ActivityCursorPagination(ActivityRepository activityRepository, JsonMapper jsonMapper) {
		this.activityRepository = activityRepository;
		this.jsonMapper = jsonMapper;
	}

	public CursorPage<Activity> page(Specification<Activity> filterSpec, Sort.Order primaryOrder, String cursorToken, int limit) {
		Sort effectiveSort = Sort.by(primaryOrder)
				.and(Sort.by(primaryOrder.isDescending() ? Sort.Order.desc("id") : Sort.Order.asc("id")));

		Specification<Activity> spec = filterSpec;
		if (cursorToken != null && !cursorToken.isBlank()) {
			ActivityCursor cursor = decode(cursorToken);
			if (!cursor.field().equals(primaryOrder.getProperty())) {
				throw new ValidationException("cursor does not match the requested sort field.", "cursor");
			}
			spec = spec.and(seekPredicate(primaryOrder, cursor));
		}

		Pageable pageable = PageRequest.of(0, limit + 1, effectiveSort);
		List<Activity> rows = activityRepository.findAll(spec, pageable).getContent();

		boolean hasMore = rows.size() > limit;
		List<Activity> page = hasMore ? rows.subList(0, limit) : rows;
		String nextCursor = hasMore ? encode(primaryOrder.getProperty(), page.get(page.size() - 1)) : null;
		return new CursorPage<>(hasMore, nextCursor, page);
	}

	private Specification<Activity> seekPredicate(Sort.Order order, ActivityCursor cursor) {
		String field = order.getProperty();
		Comparable<?> value = parseSortValue(field, cursor.value());
		boolean desc = order.isDescending();
		return (root, query, cb) -> buildSeekPredicate(root, cb, field, value, desc, cursor.id());
	}

	private <Y extends Comparable<? super Y>> jakarta.persistence.criteria.Predicate buildSeekPredicate(
			jakarta.persistence.criteria.Root<Activity> root, jakarta.persistence.criteria.CriteriaBuilder cb,
			String field, Comparable<?> rawValue, boolean desc, String cursorId) {
		@SuppressWarnings("unchecked")
		Y value = (Y) rawValue;
		jakarta.persistence.criteria.Path<Y> fieldPath = root.get(field);
		jakarta.persistence.criteria.Path<String> idPath = root.get("id");
		var fieldEqual = cb.equal(fieldPath, value);
		var idComparison = desc ? cb.lessThan(idPath, cursorId) : cb.greaterThan(idPath, cursorId);
		var fieldComparison = desc ? cb.lessThan(fieldPath, value) : cb.greaterThan(fieldPath, value);
		return cb.or(fieldComparison, cb.and(fieldEqual, idComparison));
	}

	@SuppressWarnings("unchecked")
	private Comparable<?> parseSortValue(String field, String raw) {
		return switch (field) {
			case "startDate" -> Instant.parse(raw);
			case "avgHr", "maxHr", "tss", "movingTime", "avgPower" -> Integer.valueOf(raw);
			case "distanceKm" -> Double.valueOf(raw);
			case "sport" -> Sport.valueOf(raw);
			case "environment" -> Environment.valueOf(raw);
			default -> raw;
		};
	}

	private String encode(String field, Activity lastRow) {
		Object value = switch (field) {
			case "startDate" -> lastRow.getStartDate();
			case "avgHr" -> lastRow.getAvgHr();
			case "maxHr" -> lastRow.getMaxHr();
			case "tss" -> lastRow.getTss();
			case "movingTime" -> lastRow.getMovingTime();
			case "avgPower" -> lastRow.getAvgPower();
			case "distanceKm" -> lastRow.getDistanceKm();
			case "sport" -> lastRow.getSport().name();
			case "environment" -> lastRow.getEnvironment().name();
			case "name" -> lastRow.getName();
			default -> lastRow.getId();
		};
		ActivityCursor cursor = new ActivityCursor(field, String.valueOf(value), lastRow.getId());
		return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonMapper.writeValueAsBytes(cursor));
	}

	private ActivityCursor decode(String token) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(token);
			return jsonMapper.readValue(json, ActivityCursor.class);
		}
		catch (Exception e) {
			throw new ValidationException("Invalid cursor.", "cursor");
		}
	}
}
