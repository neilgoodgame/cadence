package com.cadence.api.uploads.parsing;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import com.garmin.fit.Decode;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapMesgListener;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Parses Garmin's binary .fit format via the official Garmin FIT Java SDK ({@code com.garmin:fit}). */
public final class FitFileParser {

	public static ParsedActivity parse(InputStream inputStream) throws IOException {
		List<RecordMesg> records = new ArrayList<>();
		List<LapMesg> laps = new ArrayList<>();
		List<SessionMesg> sessions = new ArrayList<>();

		RecordMesgListener recordListener = records::add;
		LapMesgListener lapListener = laps::add;
		SessionMesgListener sessionListener = sessions::add;

		Decode decode = new Decode();
		MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
		broadcaster.addListener(recordListener);
		broadcaster.addListener(lapListener);
		broadcaster.addListener(sessionListener);
		broadcaster.run(inputStream);

		if (records.isEmpty()) {
			throw new IllegalArgumentException("No record messages found in FIT file.");
		}

		Sport sport = sessions.isEmpty() ? Sport.BIKE : mapSport(sessions.get(0).getSport());
		long startMillis = records.get(0).getTimestamp().getDate().getTime();
		Instant startDate = Instant.ofEpochMilli(startMillis);
		boolean hasGps = records.stream().anyMatch(r -> r.getPositionLat() != null && r.getPositionLong() != null);

		List<ParsedActivity.Sample> samples = new ArrayList<>(records.size());
		for (RecordMesg r : records) {
			int t = (int) ((r.getTimestamp().getDate().getTime() - startMillis) / 1000);
			Double lat = r.getPositionLat() != null ? semicirclesToDegrees(r.getPositionLat()) : null;
			Double lng = r.getPositionLong() != null ? semicirclesToDegrees(r.getPositionLong()) : null;
			Double altitude = r.getEnhancedAltitude() != null ? r.getEnhancedAltitude().doubleValue()
					: (r.getAltitude() != null ? r.getAltitude().doubleValue() : null);
			Double distanceKm = r.getDistance() != null ? r.getDistance() / 1000.0 : null;
			Double speed = r.getEnhancedSpeed() != null ? r.getEnhancedSpeed().doubleValue()
					: (r.getSpeed() != null ? r.getSpeed().doubleValue() : null);
			Integer heartrate = r.getHeartRate() != null ? r.getHeartRate().intValue() : null;
			Integer cadence = r.getCadence() != null ? r.getCadence().intValue() : null;
			samples.add(new ParsedActivity.Sample(t, lat, lng, altitude, distanceKm, heartrate, cadence, r.getPower(), speed));
		}

		List<ParsedActivity.LapSummary> lapSummaries = new ArrayList<>(laps.size());
		int index = 1;
		for (LapMesg lap : laps) {
			int duration = lap.getTotalElapsedTime() != null ? Math.round(lap.getTotalElapsedTime()) : 0;
			double distanceKm = lap.getTotalDistance() != null ? lap.getTotalDistance() / 1000.0 : 0.0;
			Integer avgHr = lap.getAvgHeartRate() != null ? lap.getAvgHeartRate().intValue() : null;
			lapSummaries.add(new ParsedActivity.LapSummary(index++, duration, distanceKm, avgHr, lap.getAvgPower()));
		}

		Environment environment = hasGps ? Environment.OUTDOOR : Environment.INDOOR;
		DistanceSource distanceSource = hasGps ? DistanceSource.GPS : DistanceSource.TRAINER;

		return new ParsedActivity(sport, environment, hasGps, startDate, "fit", distanceSource, samples, lapSummaries);
	}

	private static double semicirclesToDegrees(int semicircles) {
		return semicircles * (180.0 / Math.pow(2, 31));
	}

	private static Sport mapSport(com.garmin.fit.Sport fitSport) {
		if (fitSport == com.garmin.fit.Sport.RUNNING) {
			return Sport.RUN;
		}
		if (fitSport == com.garmin.fit.Sport.SWIMMING) {
			return Sport.SWIM;
		}
		if (fitSport == com.garmin.fit.Sport.WALKING) {
			return Sport.WALK;
		}
		return Sport.BIKE;
	}

	private FitFileParser() {
	}
}
