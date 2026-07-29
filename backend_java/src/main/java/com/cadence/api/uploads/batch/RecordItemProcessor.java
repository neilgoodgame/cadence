package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import java.time.Instant;
import org.springframework.batch.infrastructure.item.ItemProcessor;

public class RecordItemProcessor implements ItemProcessor<RecordItemProcessor.SegmentSample, RecordRow> {

	/**
	 * A sample pre-bound to the activity it belongs to. A multisport upload loads records for
	 * several activities in one step (the parent's full stream plus each leg's slice), so the
	 * binding travels with the item rather than living in the processor.
	 */
	public record SegmentSample(String activityId, Instant startDate, ParsedActivity.Sample sample) {
	}

	@Override
	public RecordRow process(SegmentSample item) {
		ParsedActivity.Sample sample = item.sample();
		return new RecordRow(
				item.activityId(),
				java.sql.Timestamp.from(item.startDate().plusSeconds(sample.t())),
				sample.t(),
				sample.power(),
				sample.heartrate(),
				sample.cadence(),
				sample.altitude(),
				sample.lat(),
				sample.lng(),
				sample.speed(),
				sample.distanceKm(),
				sample.airTemp(),
				sample.humidity(),
				sample.coreTemp(),
				sample.skinTemp(),
				sample.heatStrain());
	}
}
