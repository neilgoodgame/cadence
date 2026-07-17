package com.cadence.api.uploads.parsing;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import com.garmin.fit.Decode;
import com.garmin.fit.DeveloperField;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FileIdMesgListener;
import com.garmin.fit.GarminProduct;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapMesgListener;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import com.garmin.fit.RecordMesgListener;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/** Parses Garmin's binary .fit format via the official Garmin FIT Java SDK ({@code com.garmin:fit}). */
public final class FitFileParser {

	/**
	 * Returns one activity for a normal file. A multisport file (more than one non-transition
	 * sport session, e.g. a duathlon's run + transition + ride) returns the parent first - sport
	 * {@code MULTISPORT}, spanning every record in the file - followed by one child per session
	 * (transitions included) with its slice of the records and laps.
	 */
	public static List<ParsedActivity> parse(InputStream inputStream) throws IOException {
		List<RecordMesg> records = new ArrayList<>();
		List<LapMesg> laps = new ArrayList<>();
		List<SessionMesg> sessions = new ArrayList<>();
		List<FileIdMesg> fileIds = new ArrayList<>();

		RecordMesgListener recordListener = records::add;
		LapMesgListener lapListener = laps::add;
		SessionMesgListener sessionListener = sessions::add;
		FileIdMesgListener fileIdListener = fileIds::add;

		Decode decode = new Decode();
		MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
		broadcaster.addListener(recordListener);
		broadcaster.addListener(lapListener);
		broadcaster.addListener(sessionListener);
		broadcaster.addListener(fileIdListener);
		broadcaster.run(inputStream);

		String device = deviceName(fileIds.isEmpty() ? null : fileIds.get(0));

		if (records.isEmpty()) {
			throw new NoActivityDataException("No record messages found in FIT file.");
		}

		List<SessionMesg> ordered = sessions.stream()
				.filter(s -> s.getStartTime() != null)
				.sorted(Comparator.comparing(s -> s.getStartTime().getDate()))
				.toList();
		long sportSessionCount = ordered.stream().filter(s -> mapSport(s.getSport()) != Sport.TRANSITION).count();
		if (sportSessionCount <= 1) {
			Sport sport = sessions.isEmpty() ? Sport.BIKE : mapSport(sessions.get(0).getSport());
			return List.of(buildActivity(records, laps, sport, trainingEffects(sessions.isEmpty() ? null : sessions.get(0)), device));
		}

		// Multisport. Sessions partition the record stream into chained windows: each session's
		// window runs from its start_time to the next session's start_time (start_time has only
		// whole-second resolution while total_elapsed_time is ms-resolution and overhangs, so
		// deriving each window's end from the session's own elapsed time would drop or
		// double-assign boundary records). The last window is open-ended.
		List<ParsedActivity> result = new ArrayList<>(ordered.size() + 1);
		// TE accumulates over the whole session on the device, so the last non-transition
		// session carries the total for the parent.
		SessionMesg lastSport = null;
		for (SessionMesg session : ordered) {
			if (mapSport(session.getSport()) != Sport.TRANSITION) {
				lastSport = session;
			}
		}
		result.add(buildActivity(records, laps, Sport.MULTISPORT, trainingEffects(lastSport), device));

		for (int i = 0; i < ordered.size(); i++) {
			SessionMesg session = ordered.get(i);
			long windowStart = session.getStartTime().getDate().getTime();
			long windowEnd = i + 1 < ordered.size() ? ordered.get(i + 1).getStartTime().getDate().getTime() : Long.MAX_VALUE;

			List<RecordMesg> sliceRecords = records.stream()
					.filter(r -> inWindow(r.getTimestamp().getDate().getTime(), windowStart, windowEnd))
					.toList();
			if (sliceRecords.isEmpty()) {
				continue;
			}
			List<LapMesg> sliceLaps = laps.stream()
					.filter(l -> l.getStartTime() != null && inWindow(l.getStartTime().getDate().getTime(), windowStart, windowEnd))
					.toList();
			result.add(buildActivity(sliceRecords, sliceLaps, mapSport(session.getSport()), trainingEffects(session), device));
		}
		return result;
	}

	private static boolean inWindow(long millis, long start, long end) {
		return millis >= start && millis < end;
	}

	private static Double[] trainingEffects(SessionMesg session) {
		// Garmin's Firstbeat-derived training load, 0.0-5.0. Standard FIT session fields (not
		// developer fields) - only present on Garmin devices that run that analytics.
		Double aerobic = null;
		Double anaerobic = null;
		if (session != null) {
			if (session.getTotalTrainingEffect() != null) {
				aerobic = session.getTotalTrainingEffect().doubleValue();
			}
			if (session.getTotalAnaerobicTrainingEffect() != null) {
				anaerobic = session.getTotalAnaerobicTrainingEffect().doubleValue();
			}
		}
		return new Double[] { aerobic, anaerobic };
	}

	/**
	 * Human-readable recording device from the file_id message, e.g. "Zwift" or
	 * "Garmin Epix Gen2". Only names the SDK's profile resolves to enum strings are used;
	 * Zwift writes a product_name of garbage bytes (sometimes ASCII garbage like "&"), so
	 * product names are only kept when printable ASCII containing at least one letter.
	 */
	private static String deviceName(FileIdMesg fileId) {
		if (fileId == null || fileId.getManufacturer() == null) {
			return "";
		}
		String manufacturer = Manufacturer.getStringFromValue(fileId.getManufacturer());
		if (manufacturer.isEmpty()) {
			return "";
		}
		String product = null;
		if (fileId.getManufacturer() == Manufacturer.GARMIN && fileId.getGarminProduct() != null) {
			String name = GarminProduct.getStringFromValue(fileId.getGarminProduct());
			product = name.isEmpty() ? null : name;
		} else if (looksLikeProductName(fileId.getProductName())) {
			product = fileId.getProductName();
		}
		return product != null ? titleCase(manufacturer) + " " + titleCase(product) : titleCase(manufacturer);
	}

	private static boolean looksLikeProductName(String value) {
		return value != null && !value.isEmpty()
				&& value.chars().allMatch(c -> c >= 0x20 && c < 0x7f)
				&& value.chars().anyMatch(Character::isLetter);
	}

	private static String titleCase(String enumName) {
		StringBuilder result = new StringBuilder(enumName.length());
		for (String word : enumName.toLowerCase(Locale.ROOT).split("[_ ]+")) {
			if (word.isEmpty()) {
				continue;
			}
			if (result.length() > 0) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}

	private static ParsedActivity buildActivity(List<RecordMesg> records, List<LapMesg> laps, Sport sport, Double[] trainingEffects, String device) {
		long startMillis = records.get(0).getTimestamp().getDate().getTime();
		Instant startDate = Instant.ofEpochMilli(startMillis);
		boolean hasGps = records.stream().anyMatch(r -> r.getPositionLat() != null && r.getPositionLong() != null);

		// The file's distance stream is cumulative from the very first record; a multisport
		// leg's slice starts mid-stream, so re-base on the slice's first reading.
		Double distanceBaseKm = null;
		for (RecordMesg r : records) {
			if (r.getDistance() != null) {
				distanceBaseKm = r.getDistance() / 1000.0;
				break;
			}
		}

		List<ParsedActivity.Sample> samples = new ArrayList<>(records.size());
		for (RecordMesg r : records) {
			int t = (int) ((r.getTimestamp().getDate().getTime() - startMillis) / 1000);
			Double lat = r.getPositionLat() != null ? semicirclesToDegrees(r.getPositionLat()) : null;
			Double lng = r.getPositionLong() != null ? semicirclesToDegrees(r.getPositionLong()) : null;
			Double altitude = firstNonNull(r.getEnhancedAltitude(), r.getAltitude());
			Double distanceKm = r.getDistance() != null ? Math.max(0.0, r.getDistance() / 1000.0 - distanceBaseKm) : null;
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

		return new ParsedActivity(
				sport, environment, hasGps, startDate, "fit", device, distanceSource, samples, lapSummaries,
				trainingEffects[0], trainingEffects[1]);
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
		if (fitSport == com.garmin.fit.Sport.TRANSITION) {
			return Sport.TRANSITION;
		}
		return Sport.BIKE;
	}

	private FitFileParser() {
	}
}
