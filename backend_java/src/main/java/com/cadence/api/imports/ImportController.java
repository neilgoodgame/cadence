package com.cadence.api.imports;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.imports.dto.ImportCounts;
import com.cadence.api.imports.dto.ImportJobResponse;
import com.cadence.api.security.AccessGuard;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ImportController {

	private final ImportJobRepository importJobRepository;
	private final ImportService importService;
	private final AccessGuard accessGuard;
	private final UserService userService;
	private final CadenceProperties properties;

	public ImportController(ImportJobRepository importJobRepository, ImportService importService,
			AccessGuard accessGuard, UserService userService, CadenceProperties properties) {
		this.importJobRepository = importJobRepository;
		this.importService = importService;
		this.accessGuard = accessGuard;
		this.userService = userService;
		this.properties = properties;
	}

	@PostMapping(value = "/v1/import", consumes = "multipart/form-data")
	public ResponseEntity<ImportJobResponse> startImport(@RequestPart("file") MultipartFile file) throws IOException {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		if (file.getSize() > properties.dataImport().maxFileBytes()) {
			throw new ValidationException("Import file is too large.", "file");
		}

		ImportJob job = new ImportJob();
		job.setAthlete(athlete);
		job = importJobRepository.save(job);

		// Staged to disk synchronously (streamed, not buffered as a byte[] - this file can be up
		// to 2GB) so the async job has something stable to read once the request has returned.
		Path stagingDir = Path.of(properties.uploads().mediaRoot(), athleteId, "imports");
		Files.createDirectories(stagingDir);
		Path target = stagingDir.resolve(job.getId() + ".json.gz");
		try (InputStream in = file.getInputStream()) {
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}

		importService.runImport(job.getId(), target);

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.header(HttpHeaders.LOCATION, "/v1/import/" + job.getId())
				.header("Retry-After", "5")
				.body(toResponse(job));
	}

	@GetMapping("/v1/import/{id}")
	public ResponseEntity<ImportJobResponse> getImport(@PathVariable String id) {
		ImportJob job = importJobRepository.findById(id).orElseThrow(() -> new NotFoundException("No such import."));
		accessGuard.requireRead(job.getAthlete().getId());
		boolean inFlight = job.getStatus() == ImportStatus.QUEUED || job.getStatus() == ImportStatus.PROCESSING;
		return inFlight
				? ResponseEntity.ok().header("Retry-After", "5").body(toResponse(job))
				: ResponseEntity.ok(toResponse(job));
	}

	private static ImportJobResponse toResponse(ImportJob job) {
		ImportCounts counts = new ImportCounts(
				job.getActivitiesImported(), job.getRacesImported(), job.getWorkoutsImported(),
				job.getScheduledWorkoutsImported(), job.getThresholdHistoryImported(), job.getBikesImported(),
				job.getShoesImported(), job.getComponentsImported(), job.getItemsSkipped());
		return new ImportJobResponse(job.getId(), job.getStatus(), job.getCurrentStep(), job.getTotalItems(),
				job.getProcessedItems(), counts, job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt());
	}
}
