package com.cadence.api.export;

import com.cadence.api.common.config.CadenceProperties;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates one export job: status transitions + file lifecycle live here, the actual
 * DB-reading/serializing work lives in {@link ExportWriter} - kept as a separate bean because
 * this method's own {@code @Async} proxy would otherwise make a same-class call to a
 * {@code @Transactional} method silently skip that annotation (Spring AOP self-invocation).
 */
@Service
public class ExportService {

	private static final Logger log = LoggerFactory.getLogger(ExportService.class);

	private final ExportJobRepository exportJobRepository;
	private final ExportWriter exportWriter;
	private final CadenceProperties properties;
	private final JsonMapper jsonMapper;

	public ExportService(ExportJobRepository exportJobRepository, ExportWriter exportWriter,
			CadenceProperties properties, JsonMapper jsonMapper) {
		this.exportJobRepository = exportJobRepository;
		this.exportWriter = exportWriter;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	@Async
	public void runExport(String jobId) {
		ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
		job.setStatus(ExportStatus.PROCESSING);
		exportJobRepository.save(job);

		String athleteId = job.getAthlete().getId();
		Path exportsDir = Path.of(properties.uploads().mediaRoot(), athleteId, "exports");
		Path target = exportsDir.resolve(jobId + ".json.gz");
		try {
			Files.createDirectories(exportsDir);
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(target))), JsonEncoding.UTF8)) {
				exportWriter.write(athleteId, generator);
			}
			job.setStatus(ExportStatus.READY);
			job.setFilePath(Path.of(athleteId, "exports", jobId + ".json.gz").toString());
			job.setFileSizeBytes(Files.size(target));
			job.setCompletedAt(Instant.now());
		}
		// Throwable, not Exception: an OutOfMemoryError (Error, not Exception) from a huge account
		// has actually happened here in practice - without this the job is silently abandoned
		// PROCESSING forever, with no way for the athlete to ever find out it failed.
		catch (Throwable t) {
			log.error("Export job {} failed", jobId, t);
			job.setStatus(ExportStatus.FAILED);
			job.setErrorMessage(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
			deleteQuietly(target);
		}
		exportJobRepository.save(job);
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// Best-effort cleanup of a partially-written file - the job is already FAILED either way.
		}
	}
}
