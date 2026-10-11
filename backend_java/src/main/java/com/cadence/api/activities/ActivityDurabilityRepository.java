package com.cadence.api.activities;

import com.cadence.api.common.domain.Sport;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityDurabilityRepository extends JpaRepository<ActivityDurability, Long> {

	List<ActivityDurability> findByActivityIdOrderByThresholdAscWindowSAsc(String activityId);

	List<ActivityDurability> findByAthleteIdAndSportAndActivityDateGreaterThanEqual(
			String athleteId, Sport sport, LocalDate since);

	List<ActivityDurability> findByAthleteIdAndSport(String athleteId, Sport sport);

	// Races feature's training-block durability (RaceBlockService) - an explicit closed
	// [start, end] range rather than the open-ended "since" cutoff the period-based query
	// above uses.
	List<ActivityDurability> findByAthleteIdAndSportAndActivityDateBetween(
			String athleteId, Sport sport, LocalDate start, LocalDate end);

	void deleteByActivityId(String activityId);
}
