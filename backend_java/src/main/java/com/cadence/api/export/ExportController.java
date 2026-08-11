package com.cadence.api.export;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.export.dto.ExportJobResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

	private final ExportJobRepository exportJobRepository;
	private final ExportService exportService;
	private final AccessGuard accessGuard;
	private final UserService userService;
	private final CadenceProperties properties;

	public ExportController(ExportJobRepository exportJobRepository, ExportService exportService,
			AccessGuard accessGuard, UserService userService, CadenceProperties properties) {
		this.exportJobRepository = exportJobRepository;
		this.exportService = exportService;
		this.accessGuard = accessGuard;
		this.userService = userService;
		this.properties = properties;
	}

	@PostMapping("/v1/export")
	public ResponseEntity<ExportJobResponse> startExport(@RequestParam(required = false) Sport sport) {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		// Only one export on record per athlete - replace any previous job and file outright
		// rather than keeping a history nobody asked for.
		exportJobRepository.findByAthleteId(athleteId).ifPresent(existing -> {
			deleteFileQuietly(existing);
			exportJobRepository.delete(existing);
		});

		ExportJob job = new ExportJob();
		job.setAthlete(athlete);
		job = exportJobRepository.save(job);

		exportService.runExport(job.getId(), sport);

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.header(HttpHeaders.LOCATION, "/v1/export/" + job.getId())
				.header("Retry-After", "5")
				.body(toResponse(job));
	}

	@GetMapping("/v1/export/{id}")
	public ResponseEntity<ExportJobResponse> getExport(@PathVariable String id) {
		ExportJob job = getOwnedJob(id);
		boolean inFlight = job.getStatus() == ExportStatus.QUEUED || job.getStatus() == ExportStatus.PROCESSING;
		return inFlight
				? ResponseEntity.ok().header("Retry-After", "5").body(toResponse(job))
				: ResponseEntity.ok(toResponse(job));
	}

	@GetMapping("/v1/export/{id}/download")
	public ResponseEntity<Resource> download(@PathVariable String id) {
		ExportJob job = getOwnedJob(id);
		if (job.getStatus() != ExportStatus.READY) {
			throw new ConflictException("Export is not ready yet.");
		}
		Path file = Path.of(properties.uploads().mediaRoot(), job.getFilePath());
		String filename = "cadence-export-" + LocalDate.now() + ".json.gz";
		return ResponseEntity.ok()
				.contentType(MediaType.valueOf("application/gzip"))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.body(new FileSystemResource(file));
	}

	private ExportJob getOwnedJob(String id) {
		ExportJob job = exportJobRepository.findById(id).orElseThrow(() -> new NotFoundException("No such export."));
		accessGuard.requireRead(job.getAthlete().getId());
		return job;
	}

	private void deleteFileQuietly(ExportJob job) {
		if (job.getFilePath() == null) {
			return;
		}
		try {
			Files.deleteIfExists(Path.of(properties.uploads().mediaRoot(), job.getFilePath()));
		}
		catch (IOException ignored) {
			// Best-effort - a leftover file with no matching row is harmless.
		}
	}

	private static ExportJobResponse toResponse(ExportJob job) {
		return new ExportJobResponse(
				job.getId(), job.getStatus(), job.getCurrentStep(), job.getTotalItems(), job.getProcessedItems(),
				job.getFileSizeBytes(), job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt());
	}
}
