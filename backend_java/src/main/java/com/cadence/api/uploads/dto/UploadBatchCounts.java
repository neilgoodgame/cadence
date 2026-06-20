package com.cadence.api.uploads.dto;

public record UploadBatchCounts(int total, int ready, int processing, int failed, int duplicate) {
}
