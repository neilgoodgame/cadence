package com.cadence.api.activities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LapRepository extends JpaRepository<Lap, Long> {

	List<Lap> findByActivityIdOrderByIndex(String activityId);
}
