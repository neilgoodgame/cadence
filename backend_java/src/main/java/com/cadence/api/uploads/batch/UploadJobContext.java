package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared state across one upload job's steps. One instance per job execution (scoped by
 * {@code uploadId}, the job parameter every step is launched with) - the parsed samples are
 * the reason this exists rather than the job's {@code ExecutionContext}, which is meant for
 * small bookkeeping values, not tens of thousands of samples.
 */
@Component
@JobScope
public class UploadJobContext {

	private final String uploadId;
	private ParsedActivity parsed;
	private String activityId;

	public UploadJobContext(@Value("#{jobParameters['uploadId']}") String uploadId) {
		this.uploadId = uploadId;
	}

	public String getUploadId() {
		return uploadId;
	}

	public ParsedActivity getParsed() {
		return parsed;
	}

	public void setParsed(ParsedActivity parsed) {
		this.parsed = parsed;
	}

	public String getActivityId() {
		return activityId;
	}

	public void setActivityId(String activityId) {
		this.activityId = activityId;
	}
}
