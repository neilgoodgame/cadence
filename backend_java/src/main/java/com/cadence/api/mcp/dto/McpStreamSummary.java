package com.cadence.api.mcp.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Never the raw 1Hz {@code StreamsResponse} payload (a single hour-long activity is 3600+ points
 * per field). Reuses {@code StreamService}'s own existing {@code resolution=low} downsampling
 * (1 sample per 15s, already built for the REST endpoint) rather than a second custom sampler,
 * plus per-field min/max/avg stats computed over that same downsampled series - close enough to
 * the true full-resolution stats for a conversational "how variable was my power" question,
 * without ever materializing the full series.
 */
public record McpStreamSummary(String resolution, int sampleCount, Map<String, FieldStats> stats,
		Map<String, List<Object>> series) {

	public record FieldStats(double min, double max, double avg) {
	}

	public static McpStreamSummary from(String resolution, Map<String, Object> fields) {
		Map<String, FieldStats> stats = new LinkedHashMap<>();
		Map<String, List<Object>> series = new LinkedHashMap<>();
		int sampleCount = 0;
		for (Map.Entry<String, Object> entry : fields.entrySet()) {
			if (!(entry.getValue() instanceof List<?> values)) {
				continue;
			}
			series.put(entry.getKey(), values.stream().map(v -> (Object) v).toList());
			sampleCount = Math.max(sampleCount, values.size());
			// latlng is a List<List<Double>> (pairs), not a scalar series - averaging it makes no
			// sense, so it's kept in the series map (for e.g. a route-shape question) but skipped
			// for stats.
			if ("latlng".equals(entry.getKey())) {
				continue;
			}
			double min = Double.POSITIVE_INFINITY;
			double max = Double.NEGATIVE_INFINITY;
			double sum = 0;
			int count = 0;
			for (Object v : values) {
				if (v instanceof Number n) {
					double d = n.doubleValue();
					min = Math.min(min, d);
					max = Math.max(max, d);
					sum += d;
					count++;
				}
			}
			if (count > 0) {
				stats.put(entry.getKey(), new FieldStats(min, max, sum / count));
			}
		}
		return new McpStreamSummary(resolution, sampleCount, stats, series);
	}
}
