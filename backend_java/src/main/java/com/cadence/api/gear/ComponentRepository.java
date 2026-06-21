package com.cadence.api.gear;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComponentRepository extends JpaRepository<Component, String> {

	List<Component> findByBikeId(String bikeId);

	int countByBikeId(String bikeId);

	/**
	 * Eagerly fetches the owning bike and athlete in one query - callers immediately walk
	 * {@code component.getBike().getAthlete().getId()} for a permission check, and that
	 * two-hop lazy chain can't be resolved once the loading transaction has closed.
	 */
	@Query("select c from Component c join fetch c.bike b join fetch b.athlete where c.id = :id")
	Optional<Component> findByIdWithBikeAndAthlete(@Param("id") String id);
}
