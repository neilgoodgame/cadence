package com.cadence.api.cql.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Builds the {@code tag <name>} predicate for a resource that supports tag filtering, typically a
 * correlated {@code EXISTS} subquery so it composes correctly under arbitrary AND/OR/NOT nesting.
 */
public interface TagPredicateFactory<T> {

	Predicate forTagName(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb, String tagName);
}
