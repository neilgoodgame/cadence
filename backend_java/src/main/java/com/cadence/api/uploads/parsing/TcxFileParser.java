package com.cadence.api.uploads.parsing;

import com.cadence.api.activities.DistanceSource;
import com.cadence.api.activities.Environment;
import com.cadence.api.common.domain.Sport;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Garmin Training Center XML. {@code DistanceMeters} on a trackpoint is cumulative in
 * most exports but resets per-lap in some - the running total below detects a reset (value
 * drops below the last seen one) and carries the offset forward rather than assuming either
 * convention outright.
 */
public final class TcxFileParser {

	public static ParsedActivity parse(InputStream inputStream) throws Exception {
		Document doc = XmlParsingSupport.parse(inputStream);

		NodeList activityNodes = doc.getElementsByTagNameNS("*", "Activity");
		if (activityNodes.getLength() == 0) {
			throw new IllegalArgumentException("No Activity element found in TCX file.");
		}
		Element activity = (Element) activityNodes.item(0);
		Sport sport = mapSport(activity.getAttribute("Sport"));

		List<Element> lapElements = XmlParsingSupport.children(activity, "Lap");
		if (lapElements.isEmpty()) {
			throw new IllegalArgumentException("No Lap elements found in TCX file.");
		}

		List<ParsedActivity.Sample> samples = new ArrayList<>();
		List<ParsedActivity.LapSummary> laps = new ArrayList<>();
		Instant startDate = null;
		boolean anyGps = false;
		double distanceOffsetKm = 0.0;
		double lastRawDistanceKm = 0.0;
		double cumulativeKm = 0.0;

		int lapIndex = 1;
		for (Element lapEl : lapElements) {
			double lapStartKm = cumulativeKm;
			Instant lapStartTime = null;
			Instant lapEndTime = null;
			Long hrSum = null;
			int hrCount = 0;
			Long powerSum = null;
			int powerCount = 0;

			for (Element track : XmlParsingSupport.children(lapEl, "Track")) {
				for (Element trackpoint : XmlParsingSupport.children(track, "Trackpoint")) {
					Element timeEl = XmlParsingSupport.firstChild(trackpoint, "Time");
					if (timeEl == null) {
						continue;
					}
					Instant time = Instant.parse(timeEl.getTextContent().trim());
					if (startDate == null) {
						startDate = time;
					}
					if (lapStartTime == null) {
						lapStartTime = time;
					}
					lapEndTime = time;

					Double lat = null;
					Double lng = null;
					Element position = XmlParsingSupport.firstChild(trackpoint, "Position");
					if (position != null) {
						lat = XmlParsingSupport.textAsDouble(XmlParsingSupport.firstChild(position, "LatitudeDegrees"));
						lng = XmlParsingSupport.textAsDouble(XmlParsingSupport.firstChild(position, "LongitudeDegrees"));
						if (lat != null && lng != null) {
							anyGps = true;
						}
					}

					Double altitude = XmlParsingSupport.textAsDouble(XmlParsingSupport.firstChild(trackpoint, "AltitudeMeters"));
					Double rawDistanceM = XmlParsingSupport.textAsDouble(XmlParsingSupport.firstChild(trackpoint, "DistanceMeters"));
					if (rawDistanceM != null) {
						double rawKm = rawDistanceM / 1000.0;
						if (rawKm < lastRawDistanceKm) {
							distanceOffsetKm += lastRawDistanceKm;
						}
						lastRawDistanceKm = rawKm;
						cumulativeKm = distanceOffsetKm + rawKm;
					}

					Integer heartrate = null;
					Element hrEl = XmlParsingSupport.firstChild(trackpoint, "HeartRateBpm");
					if (hrEl != null) {
						heartrate = XmlParsingSupport.textAsInt(XmlParsingSupport.firstChild(hrEl, "Value"));
					}
					Integer cadence = XmlParsingSupport.textAsInt(XmlParsingSupport.firstChild(trackpoint, "Cadence"));
					Integer power = null;
					Element extensions = XmlParsingSupport.firstChild(trackpoint, "Extensions");
					if (extensions != null) {
						Element watts = XmlParsingSupport.firstDescendant(extensions, "Watts");
						power = XmlParsingSupport.textAsInt(watts);
					}

					if (heartrate != null) {
						hrSum = (hrSum == null ? 0 : hrSum) + heartrate;
						hrCount++;
					}
					if (power != null) {
						powerSum = (powerSum == null ? 0 : powerSum) + power;
						powerCount++;
					}

					int t = (int) (time.getEpochSecond() - startDate.getEpochSecond());
					samples.add(new ParsedActivity.Sample(
							t, lat, lng, altitude, cumulativeKm, heartrate, cadence, power, null,
							null, null, null, null, null));
				}
			}

			int duration = (lapStartTime != null && lapEndTime != null)
					? (int) (lapEndTime.getEpochSecond() - lapStartTime.getEpochSecond())
					: 0;
			Integer avgHr = hrCount > 0 ? (int) (hrSum / hrCount) : null;
			Integer avgPower = powerCount > 0 ? (int) (powerSum / powerCount) : null;
			laps.add(new ParsedActivity.LapSummary(lapIndex++, duration, cumulativeKm - lapStartKm, avgHr, avgPower));
		}

		if (samples.isEmpty() || startDate == null) {
			throw new IllegalArgumentException("No timestamped trackpoints found in TCX file.");
		}

		Environment environment = anyGps ? Environment.OUTDOOR : Environment.INDOOR;
		DistanceSource distanceSource = anyGps ? DistanceSource.GPS : DistanceSource.TRAINER;
		return new ParsedActivity(sport, environment, anyGps, startDate, "tcx", distanceSource, samples, laps, null, null);
	}

	private static Sport mapSport(String value) {
		if (value == null) {
			return Sport.RUN;
		}
		String normalized = value.trim().toLowerCase();
		if (normalized.contains("bik")) {
			return Sport.BIKE;
		}
		if (normalized.contains("swim")) {
			return Sport.SWIM;
		}
		if (normalized.contains("walk")) {
			return Sport.WALK;
		}
		return Sport.RUN;
	}

	private TcxFileParser() {
	}
}
