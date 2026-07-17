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
		RecordRow row = new RecordRow();
		row.setActivityId(item.activityId());
		row.setTs(java.sql.Timestamp.from(item.startDate().plusSeconds(sample.t())));
		row.setT(sample.t());
		row.setPower(sample.power());
		row.setHeartrate(sample.heartrate());
		row.setCadence(sample.cadence());
		row.setAltitude(sample.altitude());
		row.setLat(sample.lat());
		row.setLng(sample.lng());
		row.setSpeed(sample.speed());
		row.setDistanceKm(sample.distanceKm());
		row.setAirTemp(sample.airTemp());
		row.setHumidity(sample.humidity());
		row.setCoreTemp(sample.coreTemp());
		row.setSkinTemp(sample.skinTemp());
		row.setHeatStrain(sample.heatStrain());
		return row;
	}
}
