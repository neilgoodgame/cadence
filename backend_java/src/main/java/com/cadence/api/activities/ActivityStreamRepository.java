package com.cadence.api.activities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityStreamRepository extends JpaRepository<ActivityStream, Long> {

	Optional<ActivityStream> findByActivityIdAndFieldAndSourceId(String activityId, String field, String sourceId);

	/** Issue #503 (phase 2): the newest stored {@code field} stream on this activity whose source
	 * holds the given running-power kind - an activity may have been swapped back and forth more
	 * than once, snapshotting a new source each time (see {@code
	 * ActivitySourceService#swapPrimaryStream}'s own Javadoc), so this takes the most recently
	 * created match rather than an arbitrary one. Used by {@code
	 * RunningPowerSourceRecomputeService} to find what to re-designate as primary. */
	Optional<ActivityStream> findTopByActivity_IdAndFieldAndSource_RunningPowerKindOrderBySource_CreatedAtDesc(
			String activityId, String field, String runningPowerKind);
}
