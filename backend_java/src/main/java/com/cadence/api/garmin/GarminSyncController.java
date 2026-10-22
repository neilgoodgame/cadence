package com.cadence.api.garmin;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.garmin.dto.GarminSyncRequest;
import com.cadence.api.garmin.dto.GarminSyncRunResponse;
import com.cadence.api.garmin.dto.GarminSyncResponse;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadMapper;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.dto.UploadBatchResponse;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GarminSyncController {

	private final GarminSyncService syncService;
	private final UploadService uploadService;
	private final UploadMapper uploadMapper;
	private final UserService userService;
	private final GarminSyncHistoryService history;

	public GarminSyncController(GarminSyncService syncService, UploadService uploadService, UploadMapper uploadMapper,
			UserService userService, GarminSyncHistoryService history) {
		this.history = history;
		this.syncService = syncService;
		this.uploadService = uploadService;
		this.uploadMapper = uploadMapper;
		this.userService = userService;
	}

	@PostMapping("/v1/garmin/sync")
	public ResponseEntity<GarminSyncResponse> sync(@RequestBody(required = false) GarminSyncRequest request) {
		User owner = userService.getById(AuthContextHolder.get().sub());
		GarminSyncRequest req = request != null ? request : new GarminSyncRequest(null, null);
		GarminSyncService.Result result = syncService.sync(owner, req.limit(), req.since());
		GarminSyncRun run = result.run();

		UploadBatchResponse batchBody = null;
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
		UploadBatch batch = result.batch();
		if (batch != null) {
			batchBody = uploadMapper.toResponse(batch, uploadService.getBatchChildren(batch.getId()));
			response.header(HttpHeaders.LOCATION, "/v1/uploads/batches/" + batch.getId()).header("Retry-After", "5");
		}
		return response.body(new GarminSyncResponse(run.getId(), run.getFound(), run.getAlreadySynced(),
				run.getRequested(), run.getFailed(), run.isTruncated(), batchBody));
	}

	@GetMapping("/v1/garmin/syncs")
	public DataListResponse<GarminSyncRunResponse> recentSyncs(
			@RequestParam(name = "limit", defaultValue = "20") int limit) {
		int bounded = Math.max(1, Math.min(limit, GarminSyncService.RUNS_KEPT));
		return new DataListResponse<>(history.recent(AuthContextHolder.get().sub(), bounded));
	}
}
