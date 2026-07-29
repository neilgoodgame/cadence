package com.cadence.api.uploads.batch;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thread-safe replacement for {@code @JobScope}-proxied {@link UploadJobContext} instances,
 * keyed by {@code uploadId} (unique per job execution by construction - every launch gets its
 * own upload row). A single shared, stateless singleton: every step (and the chunk-step reader)
 * calls {@link #forUpload} with the {@code uploadId} it already has on hand (from
 * {@code JobParameters}, or captured at construction for the per-launch reader), so correctness
 * never depends on which thread happens to be running - unlike Spring Batch's
 * {@code JobSynchronizationManager}, which was verified to misbehave under genuinely concurrent
 * {@code Job.execute()} calls (see {@link UploadJobContext}'s Javadoc).
 *
 * <p>{@link #release} must be called exactly once per job execution regardless of outcome -
 * {@link UploadJobExecutionListener#afterJob} does this - or a failed/abandoned job's entry
 * leaks for the lifetime of the process.
 */
@Component
public class UploadJobContextRegistry {

	private final ConcurrentHashMap<String, UploadJobContext> contexts = new ConcurrentHashMap<>();

	public UploadJobContext forUpload(String uploadId) {
		return contexts.computeIfAbsent(uploadId, UploadJobContext::new);
	}

	public void release(String uploadId) {
		contexts.remove(uploadId);
	}
}
