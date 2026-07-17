package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared state across one upload job's steps. One instance per job execution (scoped by
 * {@code uploadId}, the job parameter every step is launched with) - the parsed samples are
 * the reason this exists rather than the job's {@code ExecutionContext}, which is meant for
 * small bookkeeping values, not tens of thousands of samples.
 *
 * <p>A normal upload produces one segment. A multisport FIT upload produces the parent segment
 * first (sport {@code multisport}, the whole record stream) followed by one segment per leg;
 * every downstream step iterates {@link #getSegments()}.
 */
@Component
@JobScope
public class UploadJobContext {

	/** One created activity plus the slice of the parsed file it was built from. */
	public record Segment(ParsedActivity parsed, String activityId) {
	}

	private final String uploadId;
	private final List<Segment> segments = new ArrayList<>();

	public UploadJobContext(@Value("#{jobParameters['uploadId']}") String uploadId) {
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
