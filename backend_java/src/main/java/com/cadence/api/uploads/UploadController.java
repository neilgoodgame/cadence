package com.cadence.api.uploads;

import com.cadence.api.security.AccessGuard;
import com.cadence.api.uploads.dto.UploadBatchResponse;
import com.cadence.api.uploads.dto.UploadResponse;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {

	private final UploadService uploadService;
	private final UploadMapper uploadMapper;
	private final UserService userService;
	private final AccessGuard accessGuard;

	public UploadController(UploadService uploadService, UploadMapper uploadMapper, UserService userService, AccessGuard accessGuard) {
		this.uploadService = uploadService;
		this.uploadMapper = uploadMapper;
		this.userService = userService;
		this.accessGuard = accessGuard;
	}

	@PostMapping(value = "/v1/activities", consumes = "multipart/form-data")
	public ResponseEntity<UploadResponse> uploadActivity(
			@RequestPart("file") MultipartFile file,
			@RequestParam(name = "weight_before_kg", required = false) Double weightBeforeKg,
			@RequestParam(name = "weight_after_kg", required = false) Double weightAfterKg,
			@RequestParam(name = "fluids_ml", required = false) Integer fluidsMl,
			@RequestParam(name = "shoe_id", required = false) String shoeId) throws IOException {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		Upload upload = uploadService.createSingleUpload(athlete, file, weightBeforeKg, weightAfterKg, fluidsMl, shoeId);
		UploadResponse body = uploadMapper.toResponse(upload);

		if (upload.getStatus() == UploadStatus.DUPLICATE) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.header(HttpHeaders.LOCATION, "/v1/uploads/" + upload.getId())
				.header("Retry-After", "2")
				.body(body);
	}

	@PostMapping(value = "/v1/activities/batch", consumes = "multipart/form-data")
	public ResponseEntity<UploadBatchResponse> uploadActivityBatch(
			@RequestPart("file") MultipartFile file,
			@RequestParam(name = "on_duplicate", defaultValue = "skip") OnDuplicate onDuplicate) throws IOException {
		String athleteId = accessGuard.effectiveAthleteId();
		accessGuard.requireWrite(athleteId);
		User athlete = userService.getById(athleteId);

		UploadBatch batch = uploadService.createBatchUpload(athlete, file, onDuplicate);
		var children = uploadService.getBatchChildren(batch.getId());
		UploadBatchResponse body = uploadMapper.toResponse(batch, children);

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.header(HttpHeaders.LOCATION, "/v1/uploads/batches/" + batch.getId())
				.header("Retry-After", "5")
				.body(body);
	}

	@GetMapping("/v1/uploads/{id}")
	public ResponseEntity<UploadResponse> getUpload(@PathVariable String id) {
		Upload upload = uploadService.getUpload(id);
		accessGuard.requireRead(upload.getAthlete().getId());
		UploadResponse body = uploadMapper.toResponse(upload);
		boolean inFlight = upload.getStatus() == UploadStatus.QUEUED || upload.getStatus() == UploadStatus.PROCESSING;
		return inFlight
				? ResponseEntity.ok().header("Retry-After", "3").body(body)
				: ResponseEntity.ok(body);
	}

	@GetMapping("/v1/uploads/batches/{id}")
	public ResponseEntity<UploadBatchResponse> getUploadBatch(@PathVariable String id) {
		UploadBatch batch = uploadService.getUploadBatch(id);
		accessGuard.requireRead(batch.getAthlete().getId());
		var children = uploadService.getBatchChildren(id);
		UploadBatchResponse body = uploadMapper.toResponse(batch, children);
		boolean inFlight = batch.getStatus() == UploadBatchStatus.UNPACKING || batch.getStatus() == UploadBatchStatus.PROCESSING;
		return inFlight
				? ResponseEntity.ok().header("Retry-After", "5").body(body)
				: ResponseEntity.ok(body);
	}
}
