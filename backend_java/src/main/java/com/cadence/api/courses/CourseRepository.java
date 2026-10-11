package com.cadence.api.courses;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, String> {

	List<Course> findByAthleteId(String athleteId);

	long countByAthleteId(String athleteId);

	@Query("select c from Course c where c.athlete.id = :athleteId and "
			+ "(lower(c.name) like lower(concat('%', :q, '%')) or lower(c.locationName) like lower(concat('%', :q, '%')))")
	List<Course> searchByAthleteId(@Param("athleteId") String athleteId, @Param("q") String q);
}
