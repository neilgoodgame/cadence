package com.cadence.api.races;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RaceCommentRepository extends JpaRepository<RaceComment, String> {

	// Needs to come back already initialized for a response DTO touching the author's name -
	// same convention as ActivityCommentRepository.findByActivityIdWithAuthorOrderByCreated.
	@Query("select c from RaceComment c join fetch c.author where c.race.id = :raceId order by c.createdAt")
	List<RaceComment> findByRaceIdWithAuthorOrderByCreatedAt(@Param("raceId") String raceId);

	List<RaceComment> findByRaceIdOrderByCreatedAt(String raceId);

	// Export's scope - every comment on any of this athlete's races.
	@Query("select c from RaceComment c where c.race.athlete.id = :athleteId order by c.createdAt")
	List<RaceComment> findByRaceAthleteIdOrderByCreatedAt(@Param("athleteId") String athleteId);

	@Query("select count(c) from RaceComment c where c.race.athlete.id = :athleteId")
	long countByRaceAthleteId(@Param("athleteId") String athleteId);
}
