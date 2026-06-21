package com.cadence.api.webhooks;

public record UploadBatchCompletedEvent(String batchId, String athleteId) {
}
