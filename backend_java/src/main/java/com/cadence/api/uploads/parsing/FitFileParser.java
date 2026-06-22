package com.cadence.api.uploads.parsing;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import com.garmin.fit.Decode;
import com.garmin.fit.DeveloperField;
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
import java.util.OptionalDouble;

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
			Double altitude = firstNonNull(r.getEnhancedAltitude(), r.getAltitude());
			Double distanceKm = r.getDistance() != null ? r.getDistance() / 1000.0 : null;
			Double speed = firstNonNull(r.getEnhancedSpeed(), r.getSpeed());
			Integer heartrate = r.getHeartRate() != null ? r.getHeartRate().intValue() : null;
			Integer cadence = r.getCadence() != null ? r.getCadence().intValue() : null;
			Integer power = r.getPower() != null ? r.getPower() : developerFieldAsInteger(r, "Power");
			// Stryd footpod developer fields: ambient temperature/humidity.
			Double airTemp = developerFieldAsDouble(r, "Stryd Temperature");
			Integer humidity = developerFieldAsInteger(r, "Stryd Humidity");
			// CORE body-temperature sensor developer fields.
			Double coreTemp = developerFieldAsDouble(r, "core_temperature");
			Double skinTemp = developerFieldAsDouble(r, "skin_temperature");
			Double heatStrain = developerFieldAsDouble(r, "heat_strain_index");
			samples.add(new ParsedActivity.Sample(
					t, lat, lng, altitude, distanceKm, heartrate, cadence, power, speed,
					airTemp, humidity, coreTemp, skinTemp, heatStrain));
		}

		List<ParsedActivity.LapSummary> lapSummaries = new ArrayList<>(laps.size());
		int index = 1;
		for (LapMesg lap : laps) {
			int duration = lap.getTotalElapsedTime() != null ? Math.round(lap.getTotalElapsedTime()) : 0;
			double distanceKm = lap.getTotalDistance() != null ? lap.getTotalDistance() / 1000.0 : 0.0;
			Integer avgHr = lap.getAvgHeartRate() != null ? lap.getAvgHeartRate().intValue() : null;
			Integer avgPower = lap.getAvgPower();
			if (avgPower == null && lap.getStartTime() != null) {
				// Third-party run-power meters (e.g. Stryd) don't fill in the lap message's own
				// avg_power summary field - only a native power meter does. Fall back to
				// averaging the already Stryd-fallback-applied per-sample power (see `power`
				// above) over the lap's time window instead of reporting it as simply missing.
				int lapStartT = (int) ((lap.getStartTime().getDate().getTime() - startMillis) / 1000);
				int lapEndT = lapStartT + duration;
				OptionalDouble mean = samples.stream()
						.filter(s -> s.t() >= lapStartT && s.t() < lapEndT && s.power() != null)
						.mapToInt(ParsedActivity.Sample::power)
						.average();
				avgPower = mean.isPresent() ? (int) Math.round(mean.getAsDouble()) : null;
			}
			lapSummaries.add(new ParsedActivity.LapSummary(index++, duration, distanceKm, avgHr, avgPower));
		}

		Environment environment = hasGps ? Environment.OUTDOOR : Environment.INDOOR;
		DistanceSource distanceSource = hasGps ? DistanceSource.GPS : DistanceSource.TRAINER;

		return new ParsedActivity(sport, environment, hasGps, startDate, "fit", distanceSource, samples, lapSummaries);
	}

	private static double semicirclesToDegrees(int semicircles) {
		return semicircles * (180.0 / Math.pow(2, 31));
	}

	/**
	 * Deliberately not a nested ternary ({@code a != null ? a.doubleValue() : (b != null ? ... : null)}):
	 * chaining ternaries that mix a primitive-typed true-branch with a boxed-typed false-branch
	 * forces the compiler to unbox the false branch to unify the expression's type, throwing an
	 * NPE exactly when both inputs are null - which a treadmill run's missing altitude/speed
	 * fields hit immediately.
	 */
	private static Double firstNonNull(Float preferred, Float fallback) {
		if (preferred != null) {
			return preferred.doubleValue();
		}
		if (fallback != null) {
			return fallback.doubleValue();
		}
		return null;
	}

	/**
	 * Looks up a developer field by name (e.g. third-party run-power meters write power as a
	 * field named {@code "Power"} rather than the FIT spec's standard {@code power} field, which
	 * is historically cycling-only; Stryd/CORE sensors write ambient and body temperature
	 * readings the spec has no native field for at all).
	 */
	private static Double developerFieldAsDouble(RecordMesg r, String fieldName) {
		for (DeveloperField field : r.getDeveloperFields()) {
			if (fieldName.equals(field.getName()) && field.getValue() instanceof Number value) {
				return value.doubleValue();
			}
		}
		return null;
	}

	private static Integer developerFieldAsInteger(RecordMesg r, String fieldName) {
		Double value = developerFieldAsDouble(r, fieldName);
		return value != null ? value.intValue() : null;
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
