package com.cadence.api.activities;

/** The opaque seek-pagination cursor for {@code GET /v1/activities}: the sort key's value at the last row seen, plus its id as a tiebreaker. */
public record ActivityCursor(String field, String value, String id) {
}
