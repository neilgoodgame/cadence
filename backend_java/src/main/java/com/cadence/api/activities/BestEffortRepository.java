package com.cadence.api.activities;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BestEffortRepository extends JpaRepository<BestEffort, Long> {

	Optional<BestEffort> findByAthleteIdAndKindAndWindow(String athleteId, BestEffortKind kind, String window);

	List<BestEffort> findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindow(
			String athleteId, BestEffortKind kind, LocalDate since);

	List<BestEffort> findByAthleteIdAndKindOrderByWindow(String athleteId, BestEffortKind kind);
}
