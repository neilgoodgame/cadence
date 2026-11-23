package com.cadence.api.activities;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StreamPatchRepository extends JpaRepository<StreamPatch, String> {

	List<StreamPatch> findByActivityIdAndField(String activityId, String field);

	List<StreamPatch> findByActivityId(String activityId);

	/** Code review finding on issue #540's own PR: something else (ActivitySourceService's own
	 * primary-source swap, or an overwrite merge) can rewrite Record's field out from under an
	 * active patch with no conflict check - a later unpatchStream would then restore the
	 * patch's now-stale previousValues right back over that legitimate new data. Call this from
	 * every such write path, in the same transaction as the write. Deliberately a bulk update
	 * (not load-then-save) - there's nothing else to do with the matched rows, and this skips
	 * loading entities this call has no other use for. A no-op when there's no active patch for
	 * this activity/field. Mirrors backend's StreamPatch.invalidate_active_for_field - keep the
	 * two in sync. */
	@Modifying
	@Query("update StreamPatch p set p.undoneAt = :now where p.activity.id = :activityId and p.field = :field "
			+ "and p.undoneAt is null")
	void invalidateActiveForField(@Param("activityId") String activityId, @Param("field") String field,
			@Param("now") Instant now);
}
