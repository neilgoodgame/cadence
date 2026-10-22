package com.cadence.api.garmin;

import com.cadence.api.garmin.dto.GarminSyncRunResponse;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadBatchRepository;
import com.cadence.api.uploads.UploadMapper;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.dto.UploadBatchResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class GarminSyncHistoryService {

	private final GarminSyncRunRepository runRepository;
	private final GarminSyncedActivityRepository syncedRepository;
	private final UploadBatchRepository batchRepository;
	private final UploadService uploadService;
	private final UploadMapper uploadMapper;

	public GarminSyncHistoryService(GarminSyncRunRepository runRepository,
			GarminSyncedActivityRepository syncedRepository, UploadBatchRepository batchRepository,
			UploadService uploadService, UploadMapper uploadMapper) {
		this.runRepository = runRepository;
		this.syncedRepository = syncedRepository;
		this.batchRepository = batchRepository;
		this.uploadService = uploadService;
		this.uploadMapper = uploadMapper;
	}

	public List<GarminSyncRunResponse> recent(String ownerId, int limit) {
		return runRepository.findByOwnerIdOrderByStartedAtDesc(ownerId, PageRequest.of(0, limit)).stream()
				.map(this::toResponse)
				.toList();
	}

	public Optional<Instant> lastRunAt(String ownerId) {
		return runRepository.findByOwnerIdOrderByStartedAtDesc(ownerId, PageRequest.of(0, 1)).stream()
				.findFirst().map(GarminSyncRun::getStartedAt);
	}

	public Optional<GarminSyncedActivity> lastActivity(String ownerId) {
		return syncedRepository.findFirstByOwnerIdOrderByStartTimeDesc(ownerId);
	}

	private GarminSyncRunResponse toResponse(GarminSyncRun run) {
		Optional<UploadBatch> batch = run.getBatchId() == null ? Optional.empty()
				: batchRepository.findById(run.getBatchId());
		List<Upload> uploads = batch.map(b -> uploadService.getBatchChildren(b.getId())).orElse(List.of());
		// Garmin names every download "<id>_ACTIVITY.fit", which is how a synced id finds its upload.
		Map<String, Upload> byFilename = new HashMap<>();
		uploads.forEach(u -> byFilename.put(u.getFilename(), u));

		List<GarminSyncRunResponse.SyncedActivity> activities = syncedRepository
				.findByRunIdOrderByStartTimeAsc(run.getId()).stream()
				.map(s -> {
					Upload upload = byFilename.get(s.getGarminActivityId() + "_ACTIVITY.fit");
					var activity = upload == null ? null : upload.getActivity();
					return new GarminSyncRunResponse.SyncedActivity(s.getGarminActivityId(), s.getStartTime(),
							upload == null ? null : upload.getStatus(),
							activity == null ? null : activity.getId(),
							activity == null ? null : activity.getName(),
							activity == null ? null : activity.getSport());
				})
				.toList();

		GarminSyncRunResponse.Batch batchBody = batch.map(b -> {
			UploadBatchResponse r = uploadMapper.toResponse(b, uploads);
			return new GarminSyncRunResponse.Batch(r.id(), r.status(), r.counts());
		}).orElse(null);
		return new GarminSyncRunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(), run.getFound(),
				run.getAlreadySynced(), run.getRequested(), run.getFailed(), run.isTruncated(), run.getError(),
				batchBody, activities);
	}
}
