package com.cadence.api.uploads.batch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.batch.infrastructure.item.ItemReader;

/**
 * Reads {@code loadRecordsStep}'s items for exactly one upload job. Deliberately a plain object
 * built fresh per launch by {@link UploadJobFactory} - not a Spring bean, not {@code @StepScope}
 * - so there is no shared-instance/thread-affinity question to get wrong: this exact instance
 * was constructed for this one {@code uploadId} and is never handed to another execution.
 *
 * <p>{@link ParseFileTasklet} (the step immediately before this one) has already populated
 * {@link UploadJobContextRegistry} by the time {@link #read()} is first called, since it runs
 * as an earlier step in the same job.
 */
public class RecordItemReader implements ItemReader<RecordItemProcessor.SegmentSample> {

	private final UploadJobContextRegistry contextRegistry;
	private final String uploadId;
	private Iterator<RecordItemProcessor.SegmentSample> items;

	public RecordItemReader(UploadJobContextRegistry contextRegistry, String uploadId) {
		this.contextRegistry = contextRegistry;
		this.uploadId = uploadId;
	}

	@Override
	public RecordItemProcessor.SegmentSample read() {
		if (items == null) {
			items = buildItems().iterator();
		}
		return items.hasNext() ? items.next() : null;
	}

	private List<RecordItemProcessor.SegmentSample> buildItems() {
		UploadJobContext context = contextRegistry.forUpload(uploadId);
		List<RecordItemProcessor.SegmentSample> items = new ArrayList<>();
		for (UploadJobContext.Segment segment : context.getSegments()) {
			for (var sample : segment.parsed().samples()) {
				items.add(new RecordItemProcessor.SegmentSample(segment.activityId(), segment.parsed().startDate(), sample));
			}
		}
		return items;
	}
}
