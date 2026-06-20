package com.cadence.api.cql.spec;

import com.cadence.api.cql.CqlException;
import com.cadence.api.cql.CqlFieldRegistry;
import com.cadence.api.cql.CqlNode;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Compiles a {@link CqlNode} AST into a Spring Data {@link Specification}. A record-pattern
 * {@code switch} walks the sealed AST directly into JPA Criteria predicates - {@code And}/{@code Or}
 * map onto {@code Specification.and}/{@code or}, {@code Cmp} onto a single comparison predicate.
 */
public final class CqlSpecification {

	public static <T> Specification<T> compile(CqlNode node, FieldMap fieldMap, TagPredicateFactory<T> tagFactory) {
		if (node == null) {
			return Specification.unrestricted();
		}
		return switch (node) {
			case CqlNode.And(var left, var right) ->
					compile(left, fieldMap, tagFactory).and(compile(right, fieldMap, tagFactory));
			case CqlNode.Or(var left, var right) ->
					compile(left, fieldMap, tagFactory).or(compile(right, fieldMap, tagFactory));
			case CqlNode.Cmp(var field, var op, var value) -> compileCmp(field, op, value, fieldMap, tagFactory);
		};
	}

	public static Sort resolveOrderBy(com.cadence.api.cql.CqlParser.Order order, FieldMap fieldMap) {
		if (order == null) {
			return Sort.unsorted();
		}
		String ormField = fieldMap.resolve(order.field());
		if (ormField == null) {
			throw new CqlException("Field \"" + order.field() + "\" is not sortable here");
		}
		return "desc".equals(order.direction()) ? Sort.by(ormField).descending() : Sort.by(ormField).ascending();
	}

	private static <T> Specification<T> compileCmp(String field, String op, Object value, FieldMap fieldMap,
			TagPredicateFactory<T> tagFactory) {
		if ("tag".equals(field)) {
			if (tagFactory == null) {
				throw new CqlException("Tag filtering is not available here");
			}
			boolean negate = "!=".equals(op);
			return (root, query, cb) -> {
				Predicate p = tagFactory.forTagName(root, query, cb, (String) value);
				return negate ? cb.not(p) : p;
			};
		}

		String ormField = fieldMap.resolve(field);
		if (ormField == null) {
			throw new CqlException("Field \"" + field + "\" is not filterable here");
		}

		CqlFieldRegistry.FieldSpec spec = CqlFieldRegistry.FIELD_SPECS.get(field);
		boolean negate = "!=".equals(op);

		return (root, query, cb) -> {
			Predicate p;
			if (spec.numeric()) {
				Path<Number> path = root.get(ormField);
				Number number = fieldMap.transformValue(field, ((Number) value).doubleValue());
				p = switch (op) {
					case "=", "!=" -> cb.equal(path, number);
					case ">" -> cb.gt(path, number);
					case "<" -> cb.lt(path, number);
					case ">=" -> cb.ge(path, number);
					case "<=" -> cb.le(path, number);
					default -> throw new CqlException("Unsupported operator \"" + op + "\"");
				};
			}
			else if ("name".equals(field)) {
				Path<String> path = root.get(ormField);
				p = cb.like(cb.lower(path), "%" + value.toString().toLowerCase() + "%");
			}
			else {
				Path<Object> path = root.get(ormField);
				p = cb.equal(path, fieldMap.coerceValue(field, value));
			}
			return negate ? cb.not(p) : p;
		};
	}

	private CqlSpecification() {
	}
}
