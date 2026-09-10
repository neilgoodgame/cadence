package com.cadence.api.workouts;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkoutMatchScanCandidateRepository extends JpaRepository<WorkoutMatchScanCandidate, Long> {

	// Fetch-joins activity - open-in-view is off, and the response mapper reads the activity's
	// own fields (name, startDate, movingTime, avgPower), which would otherwise throw
	// LazyInitializationException once this method's own transaction closes (same class of bug
	// fixed in LapRepository/LapController this session).
	@Query("select c from WorkoutMatchScanCandidate c left join fetch c.activity where c.scan.id = :scanId order by c.correlation desc")
	List<WorkoutMatchScanCandidate> findByScanIdOrderByCorrelationDescFetchActivity(@Param("scanId") String scanId);
}
