package com.cadence.api.athletes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThresholdSuggestionDismissalRepository extends JpaRepository<ThresholdSuggestionDismissal, Long> {

	List<ThresholdSuggestionDismissal> findByAthleteIdAndFieldAndKind(
			String athleteId, ThresholdField field, ThresholdSuggestionDismissal.Kind kind);

	boolean existsByAthleteIdAndFieldAndKindAndKey(
			String athleteId, ThresholdField field, ThresholdSuggestionDismissal.Kind kind, String key);

	void deleteByAthleteIdAndFieldAndKindAndKey(
			String athleteId, ThresholdField field, ThresholdSuggestionDismissal.Kind kind, String key);
}
