package com.cadence.api.activities;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BestEffortRepository extends JpaRepository<BestEffort, Long> {

	/** Used by the processing pipeline to upsert the per-activity best for a window. */
	Optional<BestEffort> findByAthleteIdAndKindAndWindowAndActivityId(
			String athleteId, BestEffortKind kind, String window, String activityId);

	/** All records for a window, best-first (for higher-is-better kinds: power, HR). */
	List<BestEffort> findByAthleteIdAndKindAndWindowOrderByValueDesc(
			String athleteId, BestEffortKind kind, String window);

	/** All records for a window, best-first for pace (lower value = faster). */
	List<BestEffort> findByAthleteIdAndKindAndWindowOrderByValueAsc(
			String athleteId, BestEffortKind kind, String window);

	List<BestEffort> findByAthleteIdAndKindAndDateGreaterThanEqualOrderByWindowAscValueDesc(
			String athleteId, BestEffortKind kind, LocalDate since);

	List<BestEffort> findByAthleteIdAndKindOrderByWindowAscValueDesc(String athleteId, BestEffortKind kind);

	/** Used by {@code BestEffortController#activityBestEffortRanks} to find which (kind, window)
	 * pairs a caller-supplied batch of activities actually holds a row for - see its Javadoc. */
	List<BestEffort> findByAthleteIdAndActivityIdIn(String athleteId, List<String> activityIds);

	void deleteByAthleteIdAndKindAndActivityId(String athleteId, BestEffortKind kind, String activityId);

	void deleteByAthleteIdAndKind(String athleteId, BestEffortKind kind);

	void deleteByAthleteId(String athleteId);
}
