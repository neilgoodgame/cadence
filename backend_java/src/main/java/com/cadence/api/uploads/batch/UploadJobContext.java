package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared state across one upload job's steps, keyed by {@code uploadId} in
 * {@link UploadJobContextRegistry} rather than Spring's {@code @JobScope} - the parsed samples
 * are the reason this exists rather than the job's {@code ExecutionContext}, which is meant for
 * small bookkeeping values, not tens of thousands of samples.
 *
 * <p>This used to be a {@code @JobScope} bean, resolved per-execution via Spring Batch's
 * thread-local {@code JobSynchronizationManager}. That was verified unsafe under genuine
 * concurrent job execution (concurrent uploads produced cross-contaminated {@code Record} rows
 * and duplicate/dropped launches), which is why ingestion was serialized to one job at a time
 * (see the old comment on {@link UploadJobLauncher}, and the writeup in
 * {@code ARCHITECTURE.md} §5.2). Every step and the chunk-step reader now resolves its instance
 * explicitly from {@link UploadJobContextRegistry} by {@code uploadId} instead of relying on
 * Spring to hand back the right instance implicitly - a plain keyed map has no thread-affinity
 * assumption to violate, so concurrent job executions are safe again (see
 * {@link UploadJobLauncher}'s worker pool).
 *
 * <p>A normal upload produces one segment. A multisport FIT upload produces the parent segment
 * first (sport {@code multisport}, the whole record stream) followed by one segment per leg;
 * every downstream step iterates {@link #getSegments()}.
 */
public class UploadJobContext {

	/** One created activity plus the slice of the parsed file it was built from. */
	public record Segment(ParsedActivity parsed, String activityId) {
	}

	private final String uploadId;
	private final List<Segment> segments = new ArrayList<>();

	public UploadJobContext(String uploadId) {
		this.uploadId = uploadId;
	}

	public String getUploadId() {
		return uploadId;
	}

	public void addSegment(ParsedActivity parsed, String activityId) {
		segments.add(new Segment(parsed, activityId));
	}

	public List<Segment> getSegments() {
		return segments;
	}

	/** The activity an upload resolves to: the multisport parent, or the only segment's activity. */
	public String getPrimaryActivityId() {
		return segments.get(0).activityId();
	}
}
