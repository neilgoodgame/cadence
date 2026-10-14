package com.cadence.api.courses;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Splits an elevation profile into flat / climb / descent sections covering its whole length.
 * Pipeline: resample to a uniform distance grid, running median (spike removal), running mean
 * (smoothing), straight-line segmentation (Douglas-Peucker or bottom-up merge), label each
 * segment by its average grade, then merge same-label neighbours and fold short segments into a
 * neighbour, to a fixpoint. Tuned on four marathon profiles - see docs/prototypes/course-sections/.
 *
 * <p>Mirrors the Python backend's courses/sections.py operation-for-operation (same loop order,
 * explicit half-up rounding), so the two produce identical output: CourseSectionsTest checks
 * this against the golden fixtures in src/test/resources/section-fixtures/, which are a copy of
 * the Python backend's courses/fixtures/sections/. Takes only distances, elevations and settings,
 * so activities can reuse it.
 */
public final class CourseSections {

	public static final String ALGORITHM_DOUGLAS_PEUCKER = "douglas_peucker";
	public static final String ALGORITHM_BOTTOM_UP = "bottom_up";

	public static final String ELEVATION_QUALITY_OK = "ok";
	public static final String ELEVATION_QUALITY_MISSING = "missing";
	public static final String ELEVATION_QUALITY_SPARSE = "sparse";

	public static final String STEEP_DESCENT = "steep_descent";
	public static final String DESCENT = "descent";
	public static final String FLAT = "flat";
	public static final String CLIMB = "climb";
	public static final String STEEP_CLIMB = "steep_climb";

	/** Elevation varying less than this over a whole track is a GPX with no real elevation (missing
	 * &lt;ele&gt;, or every point zero) rather than a genuinely flat course - even Chicago varies ~15m. */
	static final double MISSING_MIN_RANGE_M = 1.0;
	/** Median gap between elevation points above which hills between points are lost to the
	 * straight-line resampling - a route-planner export with points only at turns. */
	static final double SPARSE_MEDIAN_SPACING_M = 100.0;
	static final double STEEPEST_WINDOW_M = 100.0;
	/** Spacing of the stored elevation series (Course.elevationSeries). Sections are always
	 * calculated from that series, so a coarser resampleStepM resamples it again; finer isn't
	 * allowed (Settings.validate). */
	public static final double SERIES_STEP_M = 10.0;

	public record Settings(
			String algorithm, double flatMaxPct, double steepMinPct, double fitToleranceM, double minSectionM,
			double keepMinRiseM, double spikeWindowM, double smoothingWindowM, double resampleStepM) {

		public static final Settings DEFAULTS =
				new Settings(ALGORITHM_DOUGLAS_PEUCKER, 1.0, 4.0, 2.0, 250.0, 5.0, 30.0, 100.0, 10.0);

		public Settings withAlgorithm(String newAlgorithm) {
			return new Settings(newAlgorithm, flatMaxPct, steepMinPct, fitToleranceM, minSectionM, keepMinRiseM,
					spikeWindowM, smoothingWindowM, resampleStepM);
		}

		/** Throws IllegalArgumentException naming the first out-of-range setting. The ranges keep a
		 * marathon from being cut into thousands of sections. */
		public void validate() {
			if (!ALGORITHM_DOUGLAS_PEUCKER.equals(algorithm) && !ALGORITHM_BOTTOM_UP.equals(algorithm)) {
				throw new IllegalArgumentException(
						"algorithm must be one of " + ALGORITHM_DOUGLAS_PEUCKER + ", " + ALGORITHM_BOTTOM_UP + ".");
			}
			checkRange("flat_max_pct", flatMaxPct, 0.5, 3.0);
			checkRange("steep_min_pct", steepMinPct, 2.0, 10.0);
			checkRange("fit_tolerance_m", fitToleranceM, 0.5, 10.0);
			checkRange("min_section_m", minSectionM, 50.0, 1000.0);
			checkRange("keep_min_rise_m", keepMinRiseM, 1.0, 20.0);
			checkRange("spike_window_m", spikeWindowM, 0.0, 100.0);
			checkRange("smoothing_window_m", smoothingWindowM, 20.0, 300.0);
			checkRange("resample_step_m", resampleStepM, 10.0, 50.0);
			if (steepMinPct <= flatMaxPct) {
				throw new IllegalArgumentException("steep_min_pct must be greater than flat_max_pct.");
			}
		}

		private static void checkRange(String name, double value, double lo, double hi) {
			if (!(lo <= value && value <= hi)) {
				throw new IllegalArgumentException(name + " must be between " + fmt(lo) + " and " + fmt(hi) + ".");
			}
		}

		private static String fmt(double v) {
			return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
		}
	}

	/** One section. SNAKE_CASE would collapse "steepest100mPct" to "steepest100m_pct", hence the
	 * explicit name (see RaceBlockResponse for the same gotcha). */
	public record Section(
			String type, double startKm, double endKm, int lengthM, int gainM, int lossM, double avgGradePct,
			@JsonProperty("steepest_100m_pct") double steepest100mPct) {}

	public record Result(String elevationQuality, List<Section> sections) {}

	/** What a Course stores: the elevation quality, plus the series when it's ok. */
	public record ElevationSeries(String elevationQuality, List<Double> series) {}

	private record Seg(int i, int j) {}

	private record Track(double[] xs, double[] ys) {}

	private CourseSections() {
	}

	/** Sections covering the whole track, or none (with the reason as elevationQuality) when the
	 * elevation is missing or too sparse to trust. distancesKm must be cumulative; elevations may
	 * contain nulls (a point with no altitude). Equivalent to buildElevationSeries then
	 * sectionsFromSeries. */
	public static Result detect(List<Double> distancesKm, List<Double> elevationsM, Settings settings) {
		settings.validate();
		ElevationSeries series = buildElevationSeries(distancesKm, elevationsM);
		if (!ELEVATION_QUALITY_OK.equals(series.elevationQuality())) return new Result(series.elevationQuality(), List.of());
		return new Result(series.elevationQuality(), sectionsFromSeries(series.series(), settings));
	}

	/** The elevation quality plus, when it's ok, the raw (unsmoothed) elevation resampled to
	 * SERIES_STEP_M from the track's start. Unsmoothed so every setting, smoothing included, still
	 * applies at read time; full precision because rounding even to the centimetre shifts
	 * bottom-up's near-tied merges. */
	public static ElevationSeries buildElevationSeries(List<Double> distancesKm, List<Double> elevationsM) {
		String quality = elevationQuality(distancesKm, elevationsM);
		if (!ELEVATION_QUALITY_OK.equals(quality)) return new ElevationSeries(quality, List.of());
		Track raw = clean(distancesKm, elevationsM);
		double[] ys = resample(raw.xs(), raw.ys(), SERIES_STEP_M).ys();
		List<Double> series = new ArrayList<>(ys.length);
		for (double y : ys) series.add(y);
		return new ElevationSeries(quality, series);
	}

	/** Sections for a stored elevation series (see buildElevationSeries), positioned in km from the
	 * track's start. */
	public static List<Section> sectionsFromSeries(List<Double> series, Settings settings) {
		settings.validate();
		if (series.size() < 2) return List.of();
		double[] xs = new double[series.size()];
		double[] ys = new double[series.size()];
		for (int k = 0; k < xs.length; k++) {
			xs[k] = k * SERIES_STEP_M;
			ys[k] = series.get(k);
		}
		double step = settings.resampleStepM();
		if (step != SERIES_STEP_M) {
			Track grid = resample(xs, ys, step);
			xs = grid.xs();
			ys = grid.ys();
			if (xs.length < 2) return List.of();
		}
		ys = runningMedian(ys, halfWindow(settings.spikeWindowM(), step));
		ys = runningMean(ys, halfWindow(settings.smoothingWindowM(), step));
		List<Seg> segs = ALGORITHM_BOTTOM_UP.equals(settings.algorithm())
				? bottomUp(xs, ys, settings.fitToleranceM())
				: douglasPeucker(xs, ys, settings.fitToleranceM());
		segs = tidy(xs, ys, segs, settings);
		List<Section> sections = new ArrayList<>(segs.size());
		for (Seg seg : segs) sections.add(describe(xs, ys, seg, settings));
		return sections;
	}

	/** The climb and steep-climb sections in the shape the API's `climbs` has always had. */
	public static List<Climb> climbsFromSections(List<Section> sections) {
		List<Climb> climbs = new ArrayList<>();
		for (Section s : sections) {
			if (CLIMB.equals(s.type()) || STEEP_CLIMB.equals(s.type())) {
				climbs.add(new Climb(s.startKm(), s.endKm(), s.gainM(), s.avgGradePct()));
			}
		}
		return climbs;
	}

	public static String elevationQuality(List<Double> distancesKm, List<Double> elevationsM) {
		Track t = clean(distancesKm, elevationsM);
		double[] xs = t.xs();
		double[] ys = t.ys();
		if (xs.length < 2) return ELEVATION_QUALITY_MISSING;
		double max = ys[0];
		double min = ys[0];
		for (double y : ys) {
			if (y > max) max = y;
			if (y < min) min = y;
		}
		if (max - min < MISSING_MIN_RANGE_M) return ELEVATION_QUALITY_MISSING;
		double[] gaps = new double[xs.length - 1];
		for (int i = 0; i < gaps.length; i++) gaps[i] = xs[i + 1] - xs[i];
		if (median(gaps, 0, gaps.length) > SPARSE_MEDIAN_SPACING_M) return ELEVATION_QUALITY_SPARSE;
		return ELEVATION_QUALITY_OK;
	}

	static double roundHalfUp(double value, int digits) {
		double factor = Math.pow(10.0, digits);
		return Math.floor(value * factor + 0.5) / factor;
	}

	static int halfWindow(double windowM, double stepM) {
		return (int) Math.floor(windowM / stepM / 2.0 + 0.5);
	}

	/** Metres + elevations for points that have an elevation, keeping only strictly increasing
	 * distances (a paused activity repeats its distance). */
	private static Track clean(List<Double> distancesKm, List<Double> elevationsM) {
		if (distancesKm.size() != elevationsM.size()) {
			throw new IllegalArgumentException("distances and elevations must be the same length.");
		}
		double[] xs = new double[distancesKm.size()];
		double[] ys = new double[distancesKm.size()];
		int n = 0;
		for (int k = 0; k < distancesKm.size(); k++) {
			Double d = distancesKm.get(k);
			Double e = elevationsM.get(k);
			if (e == null || d == null) continue;
			double x = d * 1000.0;
			if (n > 0 && x <= xs[n - 1]) continue;
			xs[n] = x;
			ys[n] = e;
			n++;
		}
		return new Track(Arrays.copyOf(xs, n), Arrays.copyOf(ys, n));
	}

	private static double median(double[] values, int from, int to) {
		double[] s = Arrays.copyOfRange(values, from, to);
		Arrays.sort(s);
		int n = s.length;
		int mid = n / 2;
		if (n % 2 == 1) return s[mid];
		return (s[mid - 1] + s[mid]) / 2.0;
	}

	/** Linear interpolation onto x0, x0+step, ... up to the last point (inclusive when it falls on
	 * the grid). Inputs must be strictly increasing in x. */
	static Track resample(double[] xsM, double[] ys, double stepM) {
		double x0 = xsM[0];
		int count = (int) Math.floor((xsM[xsM.length - 1] - x0) / stepM + 1e-9) + 1;
		double[] gx = new double[count];
		double[] gy = new double[count];
		int j = 0;
		for (int k = 0; k < count; k++) {
			double x = x0 + k * stepM;
			while (j < xsM.length - 2 && xsM[j + 1] < x) j++;
			double xa = xsM[j];
			double xb = xsM[j + 1];
			double y;
			if (x <= xa) {
				y = ys[j];
			}
			else if (x >= xb) {
				y = ys[j + 1];
			}
			else {
				double t = (x - xa) / (xb - xa);
				y = ys[j] + (ys[j + 1] - ys[j]) * t;
			}
			gx[k] = x;
			gy[k] = y;
		}
		return new Track(gx, gy);
	}

	private static double[] runningMedian(double[] ys, int half) {
		int n = ys.length;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) out[i] = median(ys, Math.max(0, i - half), Math.min(n, i + half + 1));
		return out;
	}

	private static double[] runningMean(double[] ys, int half) {
		int n = ys.length;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			int lo = Math.max(0, i - half);
			int hi = Math.min(n, i + half + 1);
			double total = 0.0;
			for (int k = lo; k < hi; k++) total += ys[k];
			out[i] = total / (hi - lo);
		}
		return out;
	}

	/** Top-down: split at the point of largest vertical distance from the chord until every piece
	 * stays within eps. Iterative, emitting pieces left to right. */
	static List<Seg> douglasPeucker(double[] xs, double[] ys, double eps) {
		List<Seg> out = new ArrayList<>();
		Deque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[] {0, xs.length - 1});
		while (!stack.isEmpty()) {
			int[] top = stack.pop();
			int i = top[0];
			int j = top[1];
			if (j - i < 2) {
				out.add(new Seg(i, j));
				continue;
			}
			double slope = (ys[j] - ys[i]) / (xs[j] - xs[i]);
			int bestK = i;
			double bestDev = -1.0;
			for (int k = i; k <= j; k++) {
				double dev = Math.abs(ys[k] - (ys[i] + slope * (xs[k] - xs[i])));
				if (dev > bestDev) {
					bestK = k;
					bestDev = dev;
				}
			}
			if (bestDev > eps) {
				stack.push(new int[] {bestK, j});
				stack.push(new int[] {i, bestK});
			}
			else {
				out.add(new Seg(i, j));
			}
		}
		return out;
	}

	/** Max vertical error of the least-squares line over [i, j] inclusive. */
	static double fitError(double[] xs, double[] ys, int i, int j) {
		if (j - i < 1) return 0.0;
		int n = j - i + 1;
		double sumX = 0.0;
		double sumY = 0.0;
		for (int k = i; k <= j; k++) {
			sumX += xs[k];
			sumY += ys[k];
		}
		double meanX = sumX / n;
		double meanY = sumY / n;
		double sxx = 0.0;
		double sxy = 0.0;
		for (int k = i; k <= j; k++) {
			double dx = xs[k] - meanX;
			sxx += dx * dx;
			sxy += dx * (ys[k] - meanY);
		}
		double slope = sxy / sxx;
		double err = 0.0;
		for (int k = i; k <= j; k++) {
			double e = Math.abs(ys[k] - (meanY + slope * (xs[k] - meanX)));
			if (e > err) err = e;
		}
		return err;
	}

	private record HeapEntry(double cost, int start, int version) {}

	/** Start from 2-bin pieces; repeatedly merge the adjacent pair whose merged line fit has the
	 * smallest max error (ties: leftmost pair), until no merge stays within eps. A heap with lazy
	 * invalidation keeps this near O(n log n) instead of rescanning every pair. */
	static List<Seg> bottomUp(double[] xs, double[] ys, double eps) {
		int n = xs.length;
		List<Integer> bounds = new ArrayList<>();
		for (int b = 0; b < n - 1; b += 2) bounds.add(b);
		bounds.add(n - 1);
		int[] end = new int[n];
		int[] nxt = new int[n];
		int[] prv = new int[n];
		int[] version = new int[n];
		Arrays.fill(nxt, -1);
		Arrays.fill(prv, -1);
		Arrays.fill(version, -1);
		for (int k = 0; k < bounds.size() - 1; k++) {
			end[bounds.get(k)] = bounds.get(k + 1);
			version[bounds.get(k)] = 0;
		}
		for (int k = 0; k < bounds.size() - 2; k++) {
			nxt[bounds.get(k)] = bounds.get(k + 1);
			prv[bounds.get(k + 1)] = bounds.get(k);
		}
		PriorityQueue<HeapEntry> heap = new PriorityQueue<>(Comparator.comparingDouble(HeapEntry::cost)
				.thenComparingInt(HeapEntry::start).thenComparingInt(HeapEntry::version));
		for (int k = 0; k < bounds.size() - 2; k++) push(heap, xs, ys, end, nxt, version, bounds.get(k));
		while (!heap.isEmpty()) {
			HeapEntry top = heap.poll();
			int s = top.start();
			if (nxt[s] == -1 || version[s] != top.version()) continue;
			if (top.cost() > eps) break;
			int t = nxt[s];
			end[s] = end[t];
			version[t] = -1;
			if (nxt[t] != -1) {
				int u = nxt[t];
				nxt[t] = -1;
				nxt[s] = u;
				prv[u] = s;
			}
			else {
				nxt[s] = -1;
			}
			prv[t] = -1;
			if (nxt[s] != -1) {
				push(heap, xs, ys, end, nxt, version, s);
			}
			else {
				version[s]++;
			}
			if (prv[s] != -1) push(heap, xs, ys, end, nxt, version, prv[s]);
		}
		List<Seg> out = new ArrayList<>();
		int s = bounds.get(0);
		while (true) {
			out.add(new Seg(s, end[s]));
			if (nxt[s] == -1) return out;
			s = nxt[s];
		}
	}

	private static void push(
			PriorityQueue<HeapEntry> heap, double[] xs, double[] ys, int[] end, int[] nxt, int[] version, int s) {
		version[s]++;
		heap.add(new HeapEntry(fitError(xs, ys, s, end[nxt[s]]), s, version[s]));
	}

	static String label(double gradePct, Settings settings) {
		if (gradePct < -settings.steepMinPct()) return STEEP_DESCENT;
		if (gradePct < -settings.flatMaxPct()) return DESCENT;
		if (gradePct <= settings.flatMaxPct()) return FLAT;
		if (gradePct < settings.steepMinPct()) return CLIMB;
		return STEEP_CLIMB;
	}

	private static double grade(double[] xs, double[] ys, Seg seg) {
		return 100.0 * (ys[seg.j()] - ys[seg.i()]) / (xs[seg.j()] - xs[seg.i()]);
	}

	/** Merge same-label neighbours, then fold the single shortest too-short segment into the
	 * neighbour with the closer grade - unless it rises or drops at least keepMinRiseM, so short
	 * sharp hills survive. Repeat to a fixpoint. */
	private static List<Seg> tidy(double[] xs, double[] ys, List<Seg> segs, Settings settings) {
		while (true) {
			boolean changed = false;
			List<Seg> merged = new ArrayList<>();
			merged.add(segs.get(0));
			for (int k = 1; k < segs.size(); k++) {
				Seg seg = segs.get(k);
				Seg last = merged.get(merged.size() - 1);
				if (label(grade(xs, ys, last), settings).equals(label(grade(xs, ys, seg), settings))) {
					merged.set(merged.size() - 1, new Seg(last.i(), seg.j()));
					changed = true;
				}
				else {
					merged.add(seg);
				}
			}
			segs = merged;
			if (segs.size() > 1) {
				int shortest = -1;
				double shortestLen = 0.0;
				for (int k = 0; k < segs.size(); k++) {
					Seg seg = segs.get(k);
					double length = xs[seg.j()] - xs[seg.i()];
					if (length < settings.minSectionM() && Math.abs(ys[seg.j()] - ys[seg.i()]) < settings.keepMinRiseM()) {
						if (shortest < 0 || length < shortestLen) {
							shortest = k;
							shortestLen = length;
						}
					}
				}
				if (shortest >= 0) {
					double g = grade(xs, ys, segs.get(shortest));
					int target = -1;
					double best = 0.0;
					for (int nb : new int[] {shortest - 1, shortest + 1}) {
						if (nb >= 0 && nb < segs.size()) {
							double diff = Math.abs(grade(xs, ys, segs.get(nb)) - g);
							if (target < 0 || diff < best) {
								target = nb;
								best = diff;
							}
						}
					}
					int a = Math.min(shortest, target);
					int b = Math.max(shortest, target);
					List<Seg> next = new ArrayList<>(segs.subList(0, a));
					next.add(new Seg(segs.get(a).i(), segs.get(b).j()));
					next.addAll(segs.subList(b + 1, segs.size()));
					segs = next;
					changed = true;
				}
			}
			if (!changed) return segs;
		}
	}

	private static Section describe(double[] xs, double[] ys, Seg seg, Settings settings) {
		int i = seg.i();
		int j = seg.j();
		double gain = 0.0;
		double loss = 0.0;
		for (int k = i; k < j; k++) {
			double dy = ys[k + 1] - ys[k];
			if (dy > 0) {
				gain += dy;
			}
			else if (dy < 0) {
				loss -= dy;
			}
		}
		double g = grade(xs, ys, seg);
		int w = (int) Math.floor(STEEPEST_WINDOW_M / settings.resampleStepM() + 0.5);
		double steepest = g;
		if (w > 0 && j - i >= w) {
			steepest = 100.0 * (ys[i + w] - ys[i]) / (xs[i + w] - xs[i]);
			for (int k = i + 1; k <= j - w; k++) {
				double gw = 100.0 * (ys[k + w] - ys[k]) / (xs[k + w] - xs[k]);
				if ((g >= 0 && gw > steepest) || (g < 0 && gw < steepest)) steepest = gw;
			}
		}
		return new Section(
				label(g, settings), roundHalfUp(xs[i] / 1000.0, 2), roundHalfUp(xs[j] / 1000.0, 2),
				(int) roundHalfUp(xs[j] - xs[i], 0), (int) roundHalfUp(gain, 0), (int) roundHalfUp(loss, 0),
				roundHalfUp(g, 1), roundHalfUp(steepest, 1));
	}
}
