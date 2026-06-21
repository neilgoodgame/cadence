package com.cadence.api.uploads.batch;

import com.cadence.api.uploads.parsing.ParsedActivity;
import org.springframework.batch.infrastructure.item.ItemProcessor;

public class RecordItemProcessor implements ItemProcessor<ParsedActivity.Sample, RecordRow> {

	private final String activityId;
	private final java.time.Instant startDate;

	public RecordItemProcessor(String activityId, java.time.Instant startDate) {
		this.activityId = activityId;
		this.startDate = startDate;
	}

	@Override
	public RecordRow process(ParsedActivity.Sample sample) {
		RecordRow row = new RecordRow();
		row.setActivityId(activityId);
		row.setTs(java.sql.Timestamp.from(startDate.plusSeconds(sample.t())));
		row.setT(sample.t());
		row.setPower(sample.power());
		row.setHeartrate(sample.heartrate());
		row.setCadence(sample.cadence());
		row.setAltitude(sample.altitude());
		row.setLat(sample.lat());
		row.setLng(sample.lng());
		row.setSpeed(sample.speed());
		row.setDistanceKm(sample.distanceKm());
		return row;
	}
}
