"""Course sections: split an elevation profile into flat / climb / descent sections that
cover its whole length.

Pipeline: resample to a uniform distance grid -> running median (spike removal) -> running
mean (smoothing) -> straight-line segmentation (Douglas-Peucker or bottom-up merge) -> label
each segment by its average grade -> merge same-label neighbours and fold short segments into
a neighbour, to a fixpoint. Tuned on Leeds, Chicago, Yorkshire and Brighton marathon
profiles - see docs/prototypes/course-sections/.

The core takes only distances, elevations and settings (no Course), so activities can reuse
it. backend_java's CourseSections must produce identical output: both read the same golden
fixtures (courses/fixtures/sections/), so every operation here is written to run in the same
order as the Java port, with explicit half-up rounding rather than Python's round().
"""

import heapq
import math
from collections.abc import Sequence
from dataclasses import dataclass, field

ALGORITHM_DOUGLAS_PEUCKER = "douglas_peucker"
ALGORITHM_BOTTOM_UP = "bottom_up"
ALGORITHMS = (ALGORITHM_DOUGLAS_PEUCKER, ALGORITHM_BOTTOM_UP)

ELEVATION_QUALITY_OK = "ok"
ELEVATION_QUALITY_MISSING = "missing"
ELEVATION_QUALITY_SPARSE = "sparse"

STEEP_DESCENT = "steep_descent"
DESCENT = "descent"
FLAT = "flat"
CLIMB = "climb"
STEEP_CLIMB = "steep_climb"

# Elevation that varies less than this over a whole track is a GPX with no real elevation
# (missing <ele>, or every point zero) rather than a genuinely flat course - even Chicago
# varies by ~15m.
MISSING_MIN_RANGE_M = 1.0
# Median gap between elevation points above which hills between points are lost to the
# straight-line resampling - a route-planner export with points only at turns.
SPARSE_MEDIAN_SPACING_M = 100.0
STEEPEST_WINDOW_M = 100.0


@dataclass(frozen=True)
class SectionSettings:
    algorithm: str = ALGORITHM_DOUGLAS_PEUCKER
    flat_max_pct: float = 1.0
    steep_min_pct: float = 4.0
    fit_tolerance_m: float = 2.0
    min_section_m: float = 250.0
    keep_min_rise_m: float = 5.0
    spike_window_m: float = 30.0
    smoothing_window_m: float = 100.0
    resample_step_m: float = 10.0

    def validate(self) -> None:
        """Raises ValueError naming the first out-of-range setting. The ranges keep a
        marathon from being cut into thousands of sections."""
        if self.algorithm not in ALGORITHMS:
            raise ValueError(f"algorithm must be one of {', '.join(ALGORITHMS)}.")
        for name, lo, hi in (
            ("flat_max_pct", 0.5, 3.0),
            ("steep_min_pct", 2.0, 10.0),
            ("fit_tolerance_m", 0.5, 10.0),
            ("min_section_m", 50.0, 1000.0),
            ("keep_min_rise_m", 1.0, 20.0),
            ("spike_window_m", 0.0, 100.0),
            ("smoothing_window_m", 20.0, 300.0),
            ("resample_step_m", 10.0, 50.0),
        ):
            value = getattr(self, name)
            if not lo <= value <= hi:
                raise ValueError(f"{name} must be between {lo:g} and {hi:g}.")
        if self.steep_min_pct <= self.flat_max_pct:
            raise ValueError("steep_min_pct must be greater than flat_max_pct.")


DEFAULT_SETTINGS = SectionSettings()


@dataclass
class SectionsResult:
    elevation_quality: str
    sections: list[dict] = field(default_factory=list)


def _round_half_up(value: float, digits: int = 0) -> float:
    factor = 10.0**digits
    return math.floor(value * factor + 0.5) / factor


def _half_window(window_m: float, step_m: float) -> int:
    return int(math.floor(window_m / step_m / 2.0 + 0.5))


def _clean(distances_km: Sequence[float], elevations_m: Sequence[float | None]) -> tuple[list[float], list[float]]:
    """Metres + elevations for points that have an elevation, keeping only strictly
    increasing distances (a paused activity repeats its distance)."""
    xs: list[float] = []
    ys: list[float] = []
    for d, e in zip(distances_km, elevations_m, strict=True):
        if e is None or d is None:
            continue
        x = d * 1000.0
        if xs and x <= xs[-1]:
            continue
        xs.append(x)
        ys.append(float(e))
    return xs, ys


def _median(values: list[float]) -> float:
    s = sorted(values)
    n = len(s)
    mid = n // 2
    if n % 2 == 1:
        return s[mid]
    return (s[mid - 1] + s[mid]) / 2.0


def elevation_quality(distances_km: Sequence[float], elevations_m: Sequence[float | None]) -> str:
    xs, ys = _clean(distances_km, elevations_m)
    if len(xs) < 2 or max(ys) - min(ys) < MISSING_MIN_RANGE_M:
        return ELEVATION_QUALITY_MISSING
    gaps = [xs[i + 1] - xs[i] for i in range(len(xs) - 1)]
    if _median(gaps) > SPARSE_MEDIAN_SPACING_M:
        return ELEVATION_QUALITY_SPARSE
    return ELEVATION_QUALITY_OK


def resample(xs_m: Sequence[float], ys: Sequence[float], step_m: float) -> tuple[list[float], list[float]]:
    """Linear interpolation onto x0, x0+step, ... up to the last point (inclusive when it
    falls on the grid). Inputs must be strictly increasing in x."""
    x0 = xs_m[0]
    count = int(math.floor((xs_m[-1] - x0) / step_m + 1e-9)) + 1
    grid_x: list[float] = []
    grid_y: list[float] = []
    j = 0
    for k in range(count):
        x = x0 + k * step_m
        while j < len(xs_m) - 2 and xs_m[j + 1] < x:
            j += 1
        x_a, x_b = xs_m[j], xs_m[j + 1]
        if x <= x_a:
            y = ys[j]
        elif x >= x_b:
            y = ys[j + 1]
        else:
            t = (x - x_a) / (x_b - x_a)
            y = ys[j] + (ys[j + 1] - ys[j]) * t
        grid_x.append(x)
        grid_y.append(y)
    return grid_x, grid_y


def _running_median(ys: list[float], half: int) -> list[float]:
    n = len(ys)
    return [_median(ys[max(0, i - half) : min(n, i + half + 1)]) for i in range(n)]


def _running_mean(ys: list[float], half: int) -> list[float]:
    n = len(ys)
    out = []
    for i in range(n):
        lo, hi = max(0, i - half), min(n, i + half + 1)
        total = 0.0
        for k in range(lo, hi):
            total += ys[k]
        out.append(total / (hi - lo))
    return out


def _douglas_peucker(xs: list[float], ys: list[float], eps: float) -> list[tuple[int, int]]:
    """Top-down: split at the point of largest vertical distance from the chord until every
    piece stays within eps. Iterative (a marathon at 10m is ~4,200 points, deeper than
    Python's recursion limit allows in the worst case), emitting pieces left to right."""
    out: list[tuple[int, int]] = []
    stack = [(0, len(xs) - 1)]
    while stack:
        i, j = stack.pop()
        if j - i < 2:
            out.append((i, j))
            continue
        slope = (ys[j] - ys[i]) / (xs[j] - xs[i])
        best_k, best_dev = i, -1.0
        for k in range(i, j + 1):
            dev = abs(ys[k] - (ys[i] + slope * (xs[k] - xs[i])))
            if dev > best_dev:
                best_k, best_dev = k, dev
        if best_dev > eps:
            stack.append((best_k, j))
            stack.append((i, best_k))
        else:
            out.append((i, j))
    return out


def _fit_error(xs: list[float], ys: list[float], i: int, j: int) -> float:
    """Max vertical error of the least-squares line over [i, j] inclusive."""
    if j - i < 1:
        return 0.0
    n = j - i + 1
    sum_x = 0.0
    sum_y = 0.0
    for k in range(i, j + 1):
        sum_x += xs[k]
        sum_y += ys[k]
    mean_x = sum_x / n
    mean_y = sum_y / n
    sxx = 0.0
    sxy = 0.0
    for k in range(i, j + 1):
        dx = xs[k] - mean_x
        sxx += dx * dx
        sxy += dx * (ys[k] - mean_y)
    slope = sxy / sxx
    err = 0.0
    for k in range(i, j + 1):
        e = abs(ys[k] - (mean_y + slope * (xs[k] - mean_x)))
        if e > err:
            err = e
    return err


def _bottom_up(xs: list[float], ys: list[float], eps: float) -> list[tuple[int, int]]:
    """Start from 2-bin pieces; repeatedly merge the adjacent pair whose merged line fit has
    the smallest max error (ties: leftmost pair), until no merge stays within eps. A heap
    with lazy invalidation keeps this near O(n log n) instead of rescanning every pair."""
    n = len(xs)
    bounds = list(range(0, n - 1, 2)) + [n - 1]
    end = {bounds[k]: bounds[k + 1] for k in range(len(bounds) - 1)}  # piece start -> piece end
    nxt = {bounds[k]: bounds[k + 1] for k in range(len(bounds) - 2)}  # piece start -> next piece start
    prv = {bounds[k + 1]: bounds[k] for k in range(len(bounds) - 2)}
    version = dict.fromkeys(end, 0)
    heap: list[tuple[float, int, int]] = []  # (merged error, left piece start, version)

    def push(s: int) -> None:
        version[s] += 1
        heapq.heappush(heap, (_fit_error(xs, ys, s, end[nxt[s]]), s, version[s]))

    for s in list(nxt):
        push(s)
    while heap:
        cost, s, v = heapq.heappop(heap)
        if s not in nxt or version.get(s) != v:
            continue
        if cost > eps:
            break
        t = nxt[s]
        end[s] = end.pop(t)
        version.pop(t)
        if t in nxt:
            u = nxt.pop(t)
            nxt[s] = u
            prv[u] = s
        else:
            del nxt[s]
        prv.pop(t, None)
        if s in nxt:
            push(s)
        else:
            version[s] += 1  # drop any stale entry for s
        if s in prv:
            push(prv[s])
    out = []
    s = bounds[0]
    while True:
        out.append((s, end[s]))
        if s not in nxt:
            return out
        s = nxt[s]


def _label(grade_pct: float, settings: SectionSettings) -> str:
    if grade_pct < -settings.steep_min_pct:
        return STEEP_DESCENT
    if grade_pct < -settings.flat_max_pct:
        return DESCENT
    if grade_pct <= settings.flat_max_pct:
        return FLAT
    if grade_pct < settings.steep_min_pct:
        return CLIMB
    return STEEP_CLIMB


def _grade(xs: list[float], ys: list[float], seg: tuple[int, int]) -> float:
    i, j = seg
    return 100.0 * (ys[j] - ys[i]) / (xs[j] - xs[i])


def _tidy(
    xs: list[float], ys: list[float], segs: list[tuple[int, int]], settings: SectionSettings
) -> list[tuple[int, int]]:
    """Merge same-label neighbours, then fold the single shortest too-short segment into the
    neighbour with the closer grade - unless it rises or drops at least keep_min_rise_m, so
    short sharp hills survive. Repeat to a fixpoint."""
    while True:
        changed = False
        merged = [segs[0]]
        for seg in segs[1:]:
            if _label(_grade(xs, ys, merged[-1]), settings) == _label(_grade(xs, ys, seg), settings):
                merged[-1] = (merged[-1][0], seg[1])
                changed = True
            else:
                merged.append(seg)
        segs = merged
        if len(segs) > 1:
            shortest, shortest_len = -1, 0.0
            for k, (i, j) in enumerate(segs):
                length = xs[j] - xs[i]
                if length < settings.min_section_m and abs(ys[j] - ys[i]) < settings.keep_min_rise_m:
                    if shortest < 0 or length < shortest_len:
                        shortest, shortest_len = k, length
            if shortest >= 0:
                g = _grade(xs, ys, segs[shortest])
                target = -1
                best = 0.0
                for nb in (shortest - 1, shortest + 1):
                    if 0 <= nb < len(segs):
                        diff = abs(_grade(xs, ys, segs[nb]) - g)
                        if target < 0 or diff < best:
                            target, best = nb, diff
                a, b = min(shortest, target), max(shortest, target)
                segs = segs[:a] + [(segs[a][0], segs[b][1])] + segs[b + 1 :]
                changed = True
        if not changed:
            return segs


def _describe(xs: list[float], ys: list[float], seg: tuple[int, int], settings: SectionSettings) -> dict:
    i, j = seg
    gain = 0.0
    loss = 0.0
    for k in range(i, j):
        dy = ys[k + 1] - ys[k]
        if dy > 0:
            gain += dy
        elif dy < 0:
            loss -= dy
    grade = _grade(xs, ys, seg)
    w = int(math.floor(STEEPEST_WINDOW_M / settings.resample_step_m + 0.5))
    steepest = grade
    if w > 0 and j - i >= w:
        steepest = 100.0 * (ys[i + w] - ys[i]) / (xs[i + w] - xs[i])
        for k in range(i + 1, j - w + 1):
            g = 100.0 * (ys[k + w] - ys[k]) / (xs[k + w] - xs[k])
            if (grade >= 0 and g > steepest) or (grade < 0 and g < steepest):
                steepest = g
    return {
        "type": _label(grade, settings),
        "start_km": _round_half_up(xs[i] / 1000.0, 2),
        "end_km": _round_half_up(xs[j] / 1000.0, 2),
        "length_m": int(_round_half_up(xs[j] - xs[i])),
        "gain_m": int(_round_half_up(gain)),
        "loss_m": int(_round_half_up(loss)),
        "avg_grade_pct": _round_half_up(grade, 1),
        "steepest_100m_pct": _round_half_up(steepest, 1),
    }


def detect_sections(
    distances_km: Sequence[float],
    elevations_m: Sequence[float | None],
    settings: SectionSettings = DEFAULT_SETTINGS,
) -> SectionsResult:
    """Sections covering the whole track, or none (with the reason as elevation_quality)
    when the elevation is missing or too sparse to trust. distances_km must be cumulative;
    elevations may contain None (a point with no altitude)."""
    settings.validate()
    quality = elevation_quality(distances_km, elevations_m)
    if quality != ELEVATION_QUALITY_OK:
        return SectionsResult(quality)
    raw_x, raw_y = _clean(distances_km, elevations_m)
    xs, ys = resample(raw_x, raw_y, settings.resample_step_m)
    if len(xs) < 2:
        return SectionsResult(quality)
    step = settings.resample_step_m
    ys = _running_median(ys, _half_window(settings.spike_window_m, step))
    ys = _running_mean(ys, _half_window(settings.smoothing_window_m, step))
    if settings.algorithm == ALGORITHM_BOTTOM_UP:
        segs = _bottom_up(xs, ys, settings.fit_tolerance_m)
    else:
        segs = _douglas_peucker(xs, ys, settings.fit_tolerance_m)
    segs = _tidy(xs, ys, segs, settings)
    return SectionsResult(quality, [_describe(xs, ys, seg, settings) for seg in segs])
