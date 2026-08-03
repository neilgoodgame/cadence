package com.cadence.api.export;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportJobRepository extends JpaRepository<ExportJob, String> {

	Optional<ExportJob> findByAthleteId(String athleteId);
}
