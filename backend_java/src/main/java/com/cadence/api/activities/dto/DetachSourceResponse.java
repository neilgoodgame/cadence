package com.cadence.api.activities.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** {@code allOf: [Activity, {needs_restore}]} in the contract - a flat merge, not a nested
 * object, matching the Django backend's own {@code response["needs_restore"] = ...} shape. */
public record DetachSourceResponse(@JsonUnwrapped ActivityResponse activity, boolean needsRestore) {
}
