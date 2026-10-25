package com.cadence.api.workouts;

import com.cadence.api.activities.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Mirrors com.cadence.api.activities.ActivityTagRepository exactly, against the same Tag
 * catalog - see that interface's own comments for why each method is shaped the way it is. */
public interface WorkoutTagRepository extends JpaRepository<WorkoutTag, Long> {

	@Query("select t.name from WorkoutTag wt join wt.tag t where wt.workout.id = :workoutId order by t.name")
	List<String> findTagNamesByWorkoutId(@Param("workoutId") String workoutId);

	// WorkoutMatchPreferenceService's tag-copy-on-match - needs the actual Tag rows (to link
	// directly), not just their names.
	List<WorkoutTag> findByWorkoutId(String workoutId);

	Optional<WorkoutTag> findByWorkoutIdAndTagId(String workoutId, String tagId);

	boolean existsByWorkoutIdAndTagId(String workoutId, String tagId);

	boolean existsByTagId(String tagId);

	long countByTagId(String tagId);

	@Modifying(clearAutomatically = true)
	@Query("delete from WorkoutTag wt where wt.tag = :old and wt.workout.id in "
			+ "(select wt2.workout.id from WorkoutTag wt2 where wt2.tag = :target)")
	void deleteLinksAlreadyOnTarget(@Param("old") Tag old, @Param("target") Tag target);

	@Modifying(clearAutomatically = true)
	@Query("update WorkoutTag wt set wt.tag = :target where wt.tag = :old")
	void repointLinks(@Param("old") Tag old, @Param("target") Tag target);

	@Query("select wt.tag.id as tagId, count(wt) as usageCount from WorkoutTag wt "
			+ "where wt.tag.athlete.id = :athleteId group by wt.tag.id")
	List<TagUsageCount> countByAthleteId(@Param("athleteId") String athleteId);

	interface TagUsageCount {
		String getTagId();

		long getUsageCount();
	}
}
