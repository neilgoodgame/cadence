package com.cadence.api.gear;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoeRepository extends JpaRepository<Shoe, String> {

	// toResponse() walks shoe -> shoeModelVersion -> shoeModel (a two-hop lazy chain) after the
	// loading transaction has closed, so both need to come back already initialized.
	@Query("select s from Shoe s join fetch s.shoeModelVersion smv join fetch smv.shoeModel "
			+ "where s.athlete.id = :athleteId and s.retired = false order by s.id desc")
	List<Shoe> findByAthleteIdAndRetiredFalseOrderByIdDesc(@Param("athleteId") String athleteId);

	@Query("select s from Shoe s join fetch s.shoeModelVersion smv join fetch smv.shoeModel where s.id = :id")
	Optional<Shoe> findByIdWithCatalog(@Param("id") String id);

	boolean existsByAthleteIdAndNameIgnoreCase(String athleteId, String name);

	boolean existsByAthleteIdAndNameIgnoreCaseAndIdNot(String athleteId, String name, String excludingId);

	Optional<Shoe> findByIdAndAthleteId(String id, String athleteId);
}
