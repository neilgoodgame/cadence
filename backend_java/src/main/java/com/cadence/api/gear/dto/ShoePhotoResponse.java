package com.cadence.api.gear.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ShoePhotoResponse(
		String id, String shoeId, String contentType, LocalDate takenOn, int km, String notes, Instant created) {
}
