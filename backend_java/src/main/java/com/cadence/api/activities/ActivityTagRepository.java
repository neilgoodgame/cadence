package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityTagRepository extends JpaRepository<ActivityTag, Long> {

	@Query("select t.name from ActivityTag at join at.tag t where at.activity.id = :activityId order by t.name")
	List<String> findTagNamesByActivityId(@Param("activityId") String activityId);

	Optional<ActivityTag> findByActivityIdAndTagId(String activityId, String tagId);

	boolean existsByActivityIdAndTagId(String activityId, String tagId);
}
