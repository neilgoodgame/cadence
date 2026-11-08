package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeRuleRepository extends JpaRepository<MergeRule, String> {

	Optional<MergeRule> findByAthleteIdAndSport(String athleteId, Sport sport);

	List<MergeRule> findByAthleteIdOrderBySportAsc(String athleteId);
}
