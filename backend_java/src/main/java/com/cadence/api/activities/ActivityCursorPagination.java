package com.cadence.api.activities;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.common.domain.Sport;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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

	// avgHr/maxHr/avgPower are nullable (not every activity has HR or power data). The
	// database's default NULLS ordering (NULLS FIRST for DESC, NULLS LAST for ASC) would
	// otherwise put activities with no data at the very top of a descending sort - not
	// what "sort by heart rate" means to a user. This sentinel is safely outside any real
	// reading for these fields; COALESCE-ing to it pushes nulls to the end regardless of
	// direction. It has to be applied consistently to the page ordering, the seek/cursor
	// continuation predicate, AND the cursor encoding below - all three must agree on the
	// same substituted value or pagination would skip or repeat rows at a null boundary.
	private static final Set<String> NULLABLE_SORT_FIELDS = Set.of("avgHr", "maxHr", "avgPower");
	private static final int NULLS_LAST_SENTINEL = 100_000;

	private final ActivityRepository activityRepository;
	private final JsonMapper jsonMapper;

	public ActivityCursorPagination(ActivityRepository activityRepository, JsonMapper jsonMapper) {
		this.activityRepository = activityRepository;
		this.jsonMapper = jsonMapper;
	}

	public CursorPage<Activity> page(Specification<Activity> filterSpec, Sort.Order primaryOrder, String cursorToken, int limit) {
		String field = primaryOrder.getProperty();
		boolean desc = primaryOrder.isDescending();
		boolean nullable = NULLABLE_SORT_FIELDS.contains(field);
		int sentinel = desc ? -1 : NULLS_LAST_SENTINEL;

		Sort effectiveSort = nullable
				? Sort.unsorted() // ordering applied via the Specification below instead
				: Sort.by(primaryOrder).and(Sort.by(desc ? Sort.Order.desc("id") : Sort.Order.asc("id")));

		Specification<Activity> spec = filterSpec;
		if (nullable) {
			spec = spec.and((root, query, cb) -> {
				Expression<Integer> coalesced = cb.coalesce(root.get(field), sentinel);
				query.orderBy(
						desc ? cb.desc(coalesced) : cb.asc(coalesced),
						desc ? cb.desc(root.get("id")) : cb.asc(root.get("id")));
				return cb.conjunction();
			});
		}

		if (cursorToken != null && !cursorToken.isBlank()) {
			ActivityCursor cursor = decode(cursorToken);
			if (!cursor.field().equals(field)) {
				throw new ValidationException("cursor does not match the requested sort field.", "cursor");
			}
			spec = spec.and(seekPredicate(primaryOrder, cursor, nullable, sentinel));
		}

		Pageable pageable = PageRequest.of(0, limit + 1, effectiveSort);
		List<Activity> rows = activityRepository.findAll(spec, pageable).getContent();

		boolean hasMore = rows.size() > limit;
		List<Activity> page = hasMore ? rows.subList(0, limit) : rows;
		String nextCursor = hasMore ? encode(field, page.get(page.size() - 1), sentinel) : null;
		return new CursorPage<>(hasMore, nextCursor, page);
	}

	private Specification<Activity> seekPredicate(Sort.Order order, ActivityCursor cursor, boolean nullable, int sentinel) {
		String field = order.getProperty();
		Comparable<?> value = parseSortValue(field, cursor.value());
		boolean desc = order.isDescending();
		return (root, query, cb) -> buildSeekPredicate(root, cb, field, value, desc, cursor.id(), nullable, sentinel);
	}

	@SuppressWarnings("unchecked")
	private <Y extends Comparable<? super Y>> Predicate buildSeekPredicate(
			Root<Activity> root, jakarta.persistence.criteria.CriteriaBuilder cb,
			String field, Comparable<?> rawValue, boolean desc, String cursorId, boolean nullable, int sentinel) {
		Y value = (Y) rawValue;
		Expression<Y> comparable = nullable
				? (Expression<Y>) (Expression<?>) cb.coalesce(root.<Integer>get(field), sentinel)
				: root.get(field);
		Path<String> idPath = root.get("id");
		var fieldEqual = cb.equal(comparable, value);
		var idComparison = desc ? cb.lessThan(idPath, cursorId) : cb.greaterThan(idPath, cursorId);
		var fieldComparison = desc ? cb.lessThan(comparable, value) : cb.greaterThan(comparable, value);
		return cb.or(fieldComparison, cb.and(fieldEqual, idComparison));
	}

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

	private static Integer withSentinel(Integer value, int sentinel) {
		return value != null ? value : sentinel;
	}

	private String encode(String field, Activity lastRow, int sentinel) {
		Object value = switch (field) {
			case "startDate" -> lastRow.getStartDate();
			case "avgHr" -> withSentinel(lastRow.getAvgHr(), sentinel);
			case "maxHr" -> withSentinel(lastRow.getMaxHr(), sentinel);
			case "tss" -> lastRow.getTss();
			case "movingTime" -> lastRow.getMovingTime();
			case "avgPower" -> withSentinel(lastRow.getAvgPower(), sentinel);
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
