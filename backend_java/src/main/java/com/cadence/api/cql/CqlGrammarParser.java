package com.cadence.api.cql;

import java.util.List;
import java.util.Set;

/** Hand-rolled recursive-descent parser: {@code parseOr -> parseAnd -> parsePrimary}, implicit AND, explicit OR/parens. */
final class CqlGrammarParser {

	private static final Set<String> OPERATORS = Set.of(">", "<", ">=", "<=", "=", "!=");
	private static final int GUARD_LIMIT = 60;

	private final List<String> tokens;
	private int pos = 0;

	CqlGrammarParser(List<String> tokens) {
		this.tokens = tokens;
	}

	private String peek() {
		return pos < tokens.size() ? tokens.get(pos) : null;
	}

	private String next() {
		String t = peek();
		pos++;
		return t;
	}

	private static boolean isOp(String token) {
		return token != null && OPERATORS.contains(token);
	}

	CqlNode parseOr() {
		CqlNode left = parseAnd();
		while ("or".equals(peek())) {
			next();
			CqlNode right = parseAnd();
			left = (left != null) ? new CqlNode.Or(left, right) : right;
		}
		return left;
	}

	CqlNode parseAnd() {
		CqlNode left = parsePrimary();
		while (peek() != null && !"or".equals(peek()) && !")".equals(peek())) {
			if ("and".equals(peek())) {
				next();
			}
			CqlNode right = parsePrimary();
			if (right == null) {
				continue;
			}
			left = (left != null) ? new CqlNode.And(left, right) : right;
		}
		return left;
	}

	CqlNode parsePrimary() {
		int guard = 0;
		while (guard < GUARD_LIMIT) {
			guard++;
			String t = peek();
			if (t == null) {
				return null;
			}
			if ("(".equals(t)) {
				next();
				CqlNode expr = parseOr();
				if (")".equals(peek())) {
					next();
				}
				return expr;
			}
			if ("and".equals(t) || "or".equals(t) || ")".equals(t)) {
				return null;
			}
			if (CqlFieldRegistry.SPORT_VALUES.containsKey(t)) {
				next();
				return new CqlNode.Cmp("sport", "=", CqlFieldRegistry.SPORT_VALUES.get(t));
			}
			if (CqlFieldRegistry.ENVIRONMENT_VALUES.containsKey(t)) {
				next();
				return new CqlNode.Cmp("environment", "=", CqlFieldRegistry.ENVIRONMENT_VALUES.get(t));
			}
			if ("tag".equals(t)) {
				next();
				String v = next();
				if (v == null) {
					throw new CqlException("Expected a tag name after \"tag\"");
				}
				return new CqlNode.Cmp("tag", "=", CqlValueParser.singular(v));
			}
			if (isOp(t)) {
				String op = next();
				String v = next();
				if (v == null) {
					throw new CqlException("Expected a value after \"" + op + "\"");
				}
				CqlValueParser.Parsed parsed = CqlValueParser.parseValue(v);
				if (parsed.field() == null) {
					throw new CqlException("Add a unit (e.g. 140bpm, 10km) so I know which field \"" + v + "\" means");
				}
				return new CqlNode.Cmp(parsed.field(), op, parsed.value());
			}
			String f = CqlFieldRegistry.fieldKey(t);
			if (f != null) {
				next();
				String op = "=";
				if (isOp(peek())) {
					op = next();
				}
				String v = next();
				if (v == null) {
					throw new CqlException("Expected a value for \"" + t + "\"");
				}
				CqlFieldRegistry.FieldSpec spec = CqlFieldRegistry.FIELD_SPECS.get(f);
				if (spec.numeric()) {
					CqlValueParser.Parsed parsed = CqlValueParser.parseValue(v);
					Object value = parsed.value() != null ? parsed.value() : Double.parseDouble(v);
					return new CqlNode.Cmp(f, op, value);
				}
				Object value = "sport".equals(f) ? CqlFieldRegistry.SPORT_VALUES.getOrDefault(v, v) : CqlValueParser.singular(v);
				return new CqlNode.Cmp(f, op, value);
			}
			CqlValueParser.Parsed parsed = CqlValueParser.parseValue(t);
			if (parsed.field() != null) {
				next();
				return new CqlNode.Cmp(parsed.field(), "=", parsed.value());
			}
			next(); // unrecognized filler token - skip and keep looking
		}
		return null;
	}
}
