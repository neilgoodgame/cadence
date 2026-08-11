package com.cadence.api.races;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RaceRepository extends JpaRepository<Race, String> {
	List<Race> findByAthleteIdOrderByDateAsc(String athleteId);

	long countByAthleteId(String athleteId);

	// Export's "counts" metadata block - same effective-sport fallback as ExportWriter.writeRaces
	// (a race's own sport can be null even when it's linked to an activity - see that method's
	// Javadoc), so this has to be a query rather than a derived countByAthleteIdAndSport. Must be
	// an explicit "left join" - a bare "r.activity.sport" path expression compiles to an implicit
	// INNER join, which would silently drop every race with no linked activity, even ones whose
	// own sport matches directly via the first OR branch.
	@Query("select count(r) from Race r left join r.activity a where r.athlete.id = :athleteId and "
			+ "((r.sport is not null and r.sport = :sport) or (r.sport is null and a.sport = :sport))")
	long countByAthleteIdAndEffectiveSport(
			@Param("athleteId") String athleteId, @Param("sport") com.cadence.api.common.domain.Sport sport);
}
