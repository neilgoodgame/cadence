package com.cadence.api.imports;

import com.cadence.api.imports.dto.ImportCounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one import job: status transitions + temp-file lifecycle live here, the actual
 * parsing/DB-writing work lives in {@link ImportReader} - kept as a separate bean for the same
 * reason as ExportService/ExportWriter (an @Async method calling its own @Transactional method
 * would silently skip that annotation - Spring AOP self-invocation).
 */
@Service
public class ImportService {

	private static final Logger log = LoggerFactory.getLogger(ImportService.class);

	private final ImportJobRepository importJobRepository;
	private final ImportReader importReader;

	public ImportService(ImportJobRepository importJobRepository, ImportReader importReader) {
		this.importJobRepository = importJobRepository;
		this.importReader = importReader;
	}

	@Async
	public void runImport(String jobId, Path sourceFile) {
		ImportJob job = importJobRepository.findById(jobId).orElseThrow();
		job.setStatus(ImportStatus.PROCESSING);
		importJobRepository.save(job);

		String athleteId = job.getAthlete().getId();
		try {
			// Unlike ExportService, no REQUIRES_NEW wrapper needed here - runImport isn't itself
			// transactional (see class Javadoc) and neither is ImportReader.read, so each save
			// below already commits on its own the moment it runs.
			ImportCounts counts = importReader.read(athleteId, sourceFile, step -> {
				job.setCurrentStep(step);
				importJobRepository.save(job);
			});
			job.setActivitiesImported(counts.activitiesImported());
			job.setRacesImported(counts.racesImported());
			job.setWorkoutsImported(counts.workoutsImported());
			job.setScheduledWorkoutsImported(counts.scheduledWorkoutsImported());
			job.setBikesImported(counts.bikesImported());
			job.setShoesImported(counts.shoesImported());
			job.setComponentsImported(counts.componentsImported());
			job.setItemsSkipped(counts.itemsSkipped());
			job.setStatus(ImportStatus.READY);
			job.setCompletedAt(Instant.now());
		}
		// Throwable, not Exception - see ExportService for why (a real OutOfMemoryError, an
		// Error not an Exception, silently abandoned an export job in exactly this spot).
		catch (Throwable t) {
			log.error("Import job {} failed", jobId, t);
			job.setStatus(ImportStatus.FAILED);
			job.setErrorMessage(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
		}
		finally {
			deleteQuietly(sourceFile);
		}
		importJobRepository.save(job);
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// Best-effort - a leftover temp file is harmless, the job has already reached a terminal status either way.
		}
	}
}
