package com.cadence.api.activities;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks an activity's own per-second {@link Record} stream forward with a cursor, slicing it
 * into one segment per step - by elapsed time for a {@code TIME} step, by distance for a
 * {@code DISTANCE} one - exactly like a GPS device cutting a new lap at each of a structured
 * workout's own step boundaries. Shared by {@link LapDerivationService} (a matched workout's own
 * steps) and the custom splits mode (ad-hoc client-supplied steps) so the two can't drift apart.
 * Mirrors the Python backend's activities/record_walk.py.
 */
public final class RecordStepWalker {

	private RecordStepWalker() {}

	public enum StepType { TIME, DISTANCE }

	public record Step(StepType type, Integer durationS, Double distanceM) {}

	public record Segment(List<Record> rows, int timeS, double distanceKm) {}

	public record Result(List<Segment> segments, Segment trailing) {}

	/**
	 * Returns segments with one entry per step that had any recording left to consume, in order
	 * - a strict prefix of {@code steps} (if the recording is shorter than the plan, later steps
	 * simply produce no segment, same as an athlete who stopped early - a real, unremarkable
	 * case). {@code trailing} is a final, unlinked segment for any recording left over after the
	 * last step (the recording ran longer than the plan), or {@code null} if there wasn't any.
	 *
	 * <p>Each segment's timeS/distanceKm use the step's own planned value once its boundary is
	 * genuinely reached, not the observed sample delta - two adjacent segments share their
	 * boundary sample (it closes one segment and opens the next), so diffing each one's own
	 * first/last row independently would undercount every interior segment by one unit. The
	 * non-governing dimension (and an un-reached boundary, on either dimension) always reflects
	 * what was actually recorded.
	 */
	public static Result walk(List<Record> records, List<Step> steps) {
		int n = records.size();
		if (n == 0) {
			return new Result(List.of(), null);
		}
		// A synthetic "active time" clock, running alongside the real per-record t: it advances
		// exactly like t (and is identical to it) except that any single inter-sample gap - a
		// pause/resume leaves one, since the device stops recording rather than freezing its
		// clock - contributes at most one second, matching how a device's own laps track
		// totalTimerTime (excluding paused duration) rather than totalElapsedTime. Using this in
		// place of raw t for time-based boundaries stops a pause from being silently donated in
		// full to whichever step's boundary walk happens to cross it, which would otherwise push
		// that step's real end (and every later step's) later than the true transition and sweep
		// in samples from the wrong phase. It has to be a single running clock carried forward
		// across steps, the same way offsetT/boundary already worked - resetting it fresh at
		// each step's own start would make every step (not just the ones actually touching a
		// gap) end one sample later than its target, since adjacent segments share their
		// boundary sample.
		int[] activeT = new int[n];
		activeT[0] = records.get(0).getT();
		for (int i = 1; i < n; i++) {
			activeT[i] = activeT[i - 1] + Math.min(records.get(i).getT() - records.get(i - 1).getT(), 1);
		}

		int recordIdx = 0;
		int offsetT = activeT[0];
		double offsetDistance = records.get(0).getDistanceKm() != null ? records.get(0).getDistanceKm() : 0.0;
		List<Segment> segments = new ArrayList<>();

		for (Step step : steps) {
			if (recordIdx >= n) {
				break;
			}
			int endIdx = recordIdx;
			boolean reached;
			if (step.type() == StepType.TIME) {
				int boundary = offsetT + step.durationS();
				while (endIdx < n - 1 && activeT[endIdx] < boundary) {
					endIdx++;
				}
				reached = activeT[endIdx] >= boundary;
			}
			else {
				double boundary = offsetDistance + step.distanceM() / 1000.0;
				while (endIdx < n - 1
						&& (records.get(endIdx).getDistanceKm() == null || records.get(endIdx).getDistanceKm() < boundary)) {
					endIdx++;
				}
				Double d = records.get(endIdx).getDistanceKm();
				reached = d != null && d >= boundary;
			}

			List<Record> segmentRows = records.subList(recordIdx, endIdx + 1);
			int timeS;
			double distanceKm;
			if (step.type() == StepType.TIME) {
				timeS = reached ? step.durationS() : observedDuration(segmentRows);
				distanceKm = observedDistanceKm(segmentRows);
			}
			else {
				distanceKm = reached ? step.distanceM() / 1000.0 : observedDistanceKm(segmentRows);
				timeS = observedDuration(segmentRows);
			}
			segments.add(new Segment(segmentRows, timeS, distanceKm));

			recordIdx = endIdx + 1;
			offsetT = activeT[endIdx];
			if (records.get(endIdx).getDistanceKm() != null) {
				offsetDistance = records.get(endIdx).getDistanceKm();
			}
		}

		Segment trailing = null;
		if (recordIdx < n) {
			List<Record> trailingRows = records.subList(recordIdx, n);
			trailing = new Segment(trailingRows, observedDuration(trailingRows), observedDistanceKm(trailingRows));
		}

		return new Result(segments, trailing);
	}

	private static int observedDuration(List<Record> records) {
		return records.get(records.size() - 1).getT() - records.get(0).getT();
	}

	private static double observedDistanceKm(List<Record> records) {
		Double first = null;
		Double last = null;
		int count = 0;
		for (Record r : records) {
			Double d = r.getDistanceKm();
			if (d != null) {
				if (first == null) {
					first = d;
				}
				last = d;
				count++;
			}
		}
		return count >= 2 ? last - first : 0.0;
	}
}
