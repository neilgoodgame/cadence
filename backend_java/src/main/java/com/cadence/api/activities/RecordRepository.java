package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordRepository extends JpaRepository<Record, RecordId> {

	@Query("select r from Record r where r.id.activityId = :activityId order by r.t")
	List<Record> findByActivityIdOrderByT(@Param("activityId") String activityId);

	long countByIdActivityId(String activityId);

	boolean existsByIdActivityIdAndAirTempIsNotNull(String activityId);
}
