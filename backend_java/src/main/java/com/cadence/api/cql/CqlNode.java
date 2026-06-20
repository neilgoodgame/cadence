package com.cadence.api.cql;

/** The CQL abstract syntax tree. Sealed + records give exhaustive switch pattern matching for free. */
public sealed interface CqlNode {

	record Cmp(String field, String op, Object value) implements CqlNode {
	}

	record And(CqlNode left, CqlNode right) implements CqlNode {
	}

	record Or(CqlNode left, CqlNode right) implements CqlNode {
	}
}
