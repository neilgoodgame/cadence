package com.cadence.api.gear;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoeModelVersionRepository extends JpaRepository<ShoeModelVersion, String> {

	// :q is always a non-null string (the service passes "" for "no filter") - binding a
	// literal null here makes the PG JDBC driver mis-infer the parameter as bytea, since it
	// can't determine a type from a null value, and lower(bytea) doesn't exist.
	// "join fetch" (not a plain join) since callers read sm.manufacturer/model after the
	// loading transaction has closed.
	@Query("select smv from ShoeModelVersion smv join fetch smv.shoeModel sm "
			+ "where lower(sm.manufacturer) like lower(concat('%', :q, '%')) "
			+ "or lower(sm.model) like lower(concat('%', :q, '%')) "
			+ "order by sm.manufacturer, sm.model, smv.version")
	List<ShoeModelVersion> search(@Param("q") String q);

	@Query("select smv from ShoeModelVersion smv join fetch smv.shoeModel where smv.id = :id")
	Optional<ShoeModelVersion> findByIdWithShoeModel(@Param("id") String id);

	// findFirst - same reasoning as ShoeModelRepository.findFirstByManufacturerAndModel.
	Optional<ShoeModelVersion> findFirstByShoeModelIdAndVersion(String shoeModelId, String version);

	boolean existsByShoeModelIdAndVersionIgnoreCase(String shoeModelId, String version);

	// Fetched before a whole-model delete (shoe_model_version.shoe_model_id is ON DELETE
	// RESTRICT, not CASCADE - see V6__gear_catalog.sql), so the service must remove these
	// explicitly before the ShoeModel row itself.
	List<ShoeModelVersion> findByShoeModelId(String shoeModelId);

	// Admin shoe-catalog view: how many Shoe rows reference each of a model's versions.
	// LEFT JOIN starting from ShoeModelVersion (not Shoe) so a version with zero shoes still
	// appears in the result with usageCount 0, rather than being silently absent.
	@Query("select smv.id as shoeModelVersionId, count(s) as usageCount from ShoeModelVersion smv "
			+ "left join Shoe s on s.shoeModelVersion = smv "
			+ "where smv.shoeModel.id = :shoeModelId group by smv.id")
	List<ShoeVersionUsage> countUsageByShoeModelId(@Param("shoeModelId") String shoeModelId);

	interface ShoeVersionUsage {
		String getShoeModelVersionId();

		long getUsageCount();
	}
}
