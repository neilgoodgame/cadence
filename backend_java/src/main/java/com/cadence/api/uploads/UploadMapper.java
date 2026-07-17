package com.cadence.api.uploads;

import com.cadence.api.uploads.dto.UploadBatchCounts;
import com.cadence.api.uploads.dto.UploadBatchResponse;
import com.cadence.api.uploads.dto.UploadError;
import com.cadence.api.uploads.dto.UploadResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UploadMapper {

	public UploadResponse toResponse(Upload upload) {
		UploadError error = upload.getStatus() == UploadStatus.FAILED
				? new UploadError(upload.getErrorCode(), upload.getErrorMessage())
				: null;
		var activity = upload.getActivity();
		return new UploadResponse(
				upload.getId(), upload.getStatus(), upload.getProgress(), upload.getFilename(),
				activity != null ? activity.getId() : null,
				activity != null ? activity.getSport() : null,
				error, upload.getReceivedAt(), upload.getCompletedAt());
	}

	public UploadBatchResponse toResponse(UploadBatch batch, List<Upload> children) {
		int total = children.size();
		int ready = 0;
		int processing = 0;
		int failed = 0;
		int duplicate = 0;
		for (Upload child : children) {
			switch (child.getStatus()) {
				case READY -> ready++;
				case QUEUED, PROCESSING -> processing++;
				case FAILED -> failed++;
				case DUPLICATE -> duplicate++;
			}
		}
		int settled = ready + failed + duplicate;
		double progress = total == 0 ? 0.0 : (double) settled / total;

		List<UploadResponse> uploads = children.stream().map(this::toResponse).toList();
		return new UploadBatchResponse(
				batch.getId(), batch.getStatus(), batch.getFilename(), progress,
				new UploadBatchCounts(total, ready, processing, failed, duplicate),
				uploads, batch.getReceivedAt(), batch.getCompletedAt());
	}
}
