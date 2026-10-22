package com.cadence.api.garmin.dto;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.uploads.UploadBatchStatus;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.uploads.dto.UploadBatchCounts;
import java.time.Instant;
import java.util.List;

/**
 * One past sync attempt. {@code batch} and each activity's {@code uploadStatus}, {@code activityId},
 * {@code name} and {@code sport} are read live, so a run still being processed settles in place;
 * they are null once the batch or activity has since been deleted (the Garmin id and start time
 * are always present). {@code error} is set when the attempt failed.
 */
public record GarminSyncRunResponse(String id, Instant startedAt, Instant finishedAt, int found, int alreadySynced,
		int requested, int failed, boolean truncated, String error, Batch batch, List<SyncedActivity> activities) {

	public record Batch(String id, UploadBatchStatus status, UploadBatchCounts counts) {
	}

	public record SyncedActivity(long garminActivityId, Instant startTime, UploadStatus uploadStatus,
			String activityId, String name, Sport sport) {
	}
}
