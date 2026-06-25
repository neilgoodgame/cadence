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
 * Parses GPX (always GPS-based, so always outdoor). GPX carries no native distance or power
 * channel, so distance is computed via the Haversine formula between consecutive trackpoints,
 * and power is read from a vendor extension only on a best-effort basis.
 */
public final class GpxFileParser {

	private static final double EARTH_RADIUS_KM = 6371.0088;

	public static ParsedActivity parse(InputStream inputStream) throws Exception {
		Document doc = XmlParsingSupport.parse(inputStream);

		Sport sport = Sport.RUN;
		NodeList trkList = doc.getElementsByTagNameNS("*", "trk");
		if (trkList.getLength() > 0) {
			Element type = XmlParsingSupport.firstChild((Element) trkList.item(0), "type");
			if (type != null) {
				sport = mapSport(type.getTextContent());
			}
		}

		NodeList trksegNodes = doc.getElementsByTagNameNS("*", "trkseg");
		if (trksegNodes.getLength() == 0) {
			throw new IllegalArgumentException("No track segments found in GPX file.");
		}

		List<ParsedActivity.Sample> samples = new ArrayList<>();
		List<ParsedActivity.LapSummary> laps = new ArrayList<>();
		Instant startDate = null;
		double cumulativeKm = 0.0;
		Double prevLat = null;
		Double prevLng = null;

		for (int segIndex = 0; segIndex < trksegNodes.getLength(); segIndex++) {
			Element trkseg = (Element) trksegNodes.item(segIndex);
			List<Element> trkpts = XmlParsingSupport.children(trkseg, "trkpt");
			double segStartKm = cumulativeKm;
			Instant segStartTime = null;
			Integer segHrSum = null;
			int segHrCount = 0;

			for (Element trkpt : trkpts) {
				Double lat = parseAttr(trkpt, "lat");
				Double lng = parseAttr(trkpt, "lon");
				Element timeEl = XmlParsingSupport.firstChild(trkpt, "time");
				if (timeEl == null) {
					continue;
				}
				Instant time = Instant.parse(timeEl.getTextContent().trim());
				if (startDate == null) {
					startDate = time;
				}
				if (segStartTime == null) {
					segStartTime = time;
				}

				if (prevLat != null && lat != null && lng != null) {
					cumulativeKm += haversineKm(prevLat, prevLng, lat, lng);
				}
				if (lat != null && lng != null) {
					prevLat = lat;
					prevLng = lng;
				}

				Double altitude = XmlParsingSupport.textAsDouble(XmlParsingSupport.firstChild(trkpt, "ele"));
				Integer heartrate = XmlParsingSupport.textAsInt(XmlParsingSupport.firstDescendant(trkpt, "hr"));
				Integer cadence = XmlParsingSupport.textAsInt(XmlParsingSupport.firstDescendant(trkpt, "cad"));
				Integer power = XmlParsingSupport.textAsInt(XmlParsingSupport.firstDescendant(trkpt, "power"));

				if (heartrate != null) {
					segHrSum = (segHrSum == null ? 0 : segHrSum) + heartrate;
					segHrCount++;
				}

				int t = (int) (time.getEpochSecond() - startDate.getEpochSecond());
				samples.add(new ParsedActivity.Sample(
						t, lat, lng, altitude, cumulativeKm, heartrate, cadence, power, null,
						null, null, null, null, null));
			}

			if (!trkpts.isEmpty() && segStartTime != null) {
				Element lastTimeEl = XmlParsingSupport.firstChild(trkpts.get(trkpts.size() - 1), "time");
				int duration = lastTimeEl != null
						? (int) (Instant.parse(lastTimeEl.getTextContent().trim()).getEpochSecond() - segStartTime.getEpochSecond())
						: 0;
				Integer avgHr = segHrCount > 0 ? segHrSum / segHrCount : null;
				laps.add(new ParsedActivity.LapSummary(segIndex + 1, duration, cumulativeKm - segStartKm, avgHr, null));
			}
		}

		if (samples.isEmpty() || startDate == null) {
			throw new IllegalArgumentException("No timestamped trackpoints found in GPX file.");
		}

		return new ParsedActivity(sport, Environment.OUTDOOR, true, startDate, "gpx", DistanceSource.GPS, samples, laps, null, null);
	}

	private static Double parseAttr(Element element, String name) {
		String value = element.getAttribute(name);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(value);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
				* Math.sin(dLng / 2) * Math.sin(dLng / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return EARTH_RADIUS_KM * c;
	}

	private static Sport mapSport(String type) {
		if (type == null) {
			return Sport.RUN;
		}
		String normalized = type.trim().toLowerCase();
		if (normalized.contains("bik") || normalized.contains("cycl") || normalized.contains("ride")) {
			return Sport.BIKE;
		}
		if (normalized.contains("swim")) {
			return Sport.SWIM;
		}
		if (normalized.contains("walk") || normalized.contains("hik")) {
			return Sport.WALK;
		}
		return Sport.RUN;
	}

	private GpxFileParser() {
	}
}
