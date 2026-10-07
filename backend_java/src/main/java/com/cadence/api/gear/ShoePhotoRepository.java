package com.cadence.api.gear;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoePhotoRepository extends JpaRepository<ShoePhoto, String> {

	List<ShoePhoto> findByShoeIdOrderByTakenOnAscCreatedAsc(String shoeId);

	// join fetch - callers (ShoeController's image endpoint) read photo.shoe.athlete after the
	// loading transaction has closed, same reasoning as ShoeRepository.findByIdWithCatalog.
	@Query("select p from ShoePhoto p join fetch p.shoe s join fetch s.athlete where p.id = :id")
	Optional<ShoePhoto> findByIdWithShoeAndAthlete(@Param("id") String id);
}
