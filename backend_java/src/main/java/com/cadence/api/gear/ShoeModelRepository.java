package com.cadence.api.gear;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShoeModelRepository extends JpaRepository<ShoeModel, String> {

	// findFirst, not a plain derived Optional query: this catalog is shared across every athlete
	// with no uniqueness constraint on (manufacturer, model), so more than one match is a real
	// possibility (not just a test artifact) - findFirst adds an implicit LIMIT 1 instead of
	// throwing IncorrectResultSizeDataAccessException on a legitimate duplicate.
	Optional<ShoeModel> findFirstByManufacturerAndModel(String manufacturer, String model);

	// Case-insensitive variant for the Admin screen's create-or-append dedup, which (unlike
	// ImportReader's exact-match lookup above) should treat "nike"/"Nike" as the same model.
	Optional<ShoeModel> findFirstByManufacturerIgnoreCaseAndModelIgnoreCase(String manufacturer, String model);
}
