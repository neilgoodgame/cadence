package com.cadence.api.activities;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityTagRepository extends JpaRepository<ActivityTag, Long> {

	@Query("select t.name from ActivityTag at join at.tag t where at.activity.id = :activityId order by t.name")
	List<String> findTagNamesByActivityId(@Param("activityId") String activityId);

	Optional<ActivityTag> findByActivityIdAndTagId(String activityId, String tagId);

	boolean existsByActivityIdAndTagId(String activityId, String tagId);

	boolean existsByTagId(String tagId);

	long countByTagId(String tagId);

	// Both used by TagService.renameTag's merge path - bulk SQL rather than loading every
	// ActivityTag row and re-saving it, which tripped Hibernate's unsaved-transient-instance
	// check on PrefixedIdEntity's app-assigned (non-generated) ids and would scale as a per-row
	// loop against a heavily-used tag anyway.
	@Modifying(clearAutomatically = true)
	@Query("delete from ActivityTag at where at.tag = :old and at.activity.id in "
			+ "(select at2.activity.id from ActivityTag at2 where at2.tag = :target)")
	void deleteLinksAlreadyOnTarget(@Param("old") Tag old, @Param("target") Tag target);

	@Modifying(clearAutomatically = true)
	@Query("update ActivityTag at set at.tag = :target where at.tag = :old")
	void repointLinks(@Param("old") Tag old, @Param("target") Tag target);

	@Query("select at.tag.id as tagId, count(at) as usageCount from ActivityTag at "
			+ "where at.tag.athlete.id = :athleteId group by at.tag.id")
	List<TagUsageCount> countByAthleteId(@Param("athleteId") String athleteId);

	interface TagUsageCount {
		String getTagId();

		long getUsageCount();
	}
}
