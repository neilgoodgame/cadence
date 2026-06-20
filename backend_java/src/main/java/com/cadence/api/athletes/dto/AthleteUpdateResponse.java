package com.cadence.api.athletes.dto;

import com.cadence.api.users.dto.UserResponse;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** {@code allOf: [Athlete, {zones_recomputed}]} in the contract - a flat merge, not a nested object. */
public record AthleteUpdateResponse(@JsonUnwrapped UserResponse athlete, List<String> zonesRecomputed) {
}
