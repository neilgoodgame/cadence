package com.cadence.api.cql.spec;

/** Maps a CQL field name (e.g. {@code hr}, {@code distance}) to a resource's ORM property path (e.g. {@code avgHr}). */
public interface FieldMap {

	/** Returns the ORM property name for a CQL field, or {@code null} if that field isn't filterable/sortable here. */
	String resolve(String cqlField);

	/**
	 * Rescales a parsed numeric value from the CQL field's documented unit into whatever unit
	 * the resolved ORM column actually stores. Most fields need no conversion (the units already
	 * match, e.g. {@code distance} is documented in km and {@code distanceKm} is stored in km) -
	 * override this only where they genuinely don't.
	 */
	default double transformValue(String cqlField, double rawValue) {
		return rawValue;
	}

	/**
	 * Coerces a parsed categorical (non-numeric) value into whatever type the resolved ORM
	 * property actually holds - e.g. the string {@code "run"} into a {@code Sport} enum
	 * constant. Identity by default, since most categorical fields are plain strings.
	 */
	default Object coerceValue(String cqlField, Object rawValue) {
		return rawValue;
	}
}
