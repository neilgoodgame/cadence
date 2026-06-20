package com.cadence.api.activities;

import com.cadence.api.cql.spec.TagPredicateFactory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/** {@code tag <name>} compiles to a correlated EXISTS subquery so it composes correctly under arbitrary AND/OR/NOT nesting. */
public final class ActivityTagPredicateFactory implements TagPredicateFactory<Activity> {

	@Override
	public Predicate forTagName(Root<Activity> root, CriteriaQuery<?> query, CriteriaBuilder cb, String tagName) {
		Subquery<Long> subquery = query.subquery(Long.class);
		Root<ActivityTag> activityTag = subquery.from(ActivityTag.class);
		subquery.select(activityTag.get("id"));
		subquery.where(
				cb.equal(activityTag.get("activity"), root),
				cb.equal(cb.lower(activityTag.get("tag").get("name")), tagName.toLowerCase()));
		return cb.exists(subquery);
	}
}
