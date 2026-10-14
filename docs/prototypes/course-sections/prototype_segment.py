"""Prototype: segment an elevation profile into climbs / descents / flats.

Superseded by backend/courses/sections.py (and backend_java's CourseSections); kept as the
record of how the settings were chosen. Usage: python prototype_segment.py <profile.json> <out.json>,
with a profile from backend/courses/fixtures/sections/. Needs numpy.

Pipeline: resample to uniform grid -> median + mean smoothing -> piecewise-linear
segmentation (bottom-up merge, or Douglas-Peucker for comparison) -> grade-band labels
-> merge same-label neighbours -> absorb short segments.
"""
import json
import sys

import numpy as np

STEP_M = 10.0          # resample grid
MEDIAN_M = 30.0        # spike removal
MEAN_M = 100.0         # smoothing
EPS_M = 2.0            # max vertical error of a straight-line fit for a segment
MIN_SEG_M = 250.0      # shorter segments get absorbed into a neighbour
MIN_RISE_M = 5.0       # ...unless they are a climb/descent rising or dropping at least this much

# Grade bands (running). Upper bounds, exclusive - except flat, which is
# inclusive at both ends so exactly -1% and +1% both count as flat.
BANDS = [
    (-4.0, "steep_descent"),
    (-1.0, "descent"),
    (1.0, "flat"),
    (4.0, "climb"),
    (float("inf"), "steep_climb"),
]


def label(grade_pct):
    for hi, name in BANDS:
        if grade_pct < hi or (name == "flat" and grade_pct <= hi):
            return name
    return BANDS[-1][1]


def resample(dist_km, alt):
    d = np.asarray(dist_km) * 1000.0
    a = np.asarray(alt, dtype=float)
    keep = np.concatenate([[True], np.diff(d) > 0])
    d, a = d[keep], a[keep]
    grid = np.arange(0.0, d[-1] + 1e-9, STEP_M)
    return grid, np.interp(grid, d, a)


def rolling(x, window_m, fn):
    half = int(round(window_m / STEP_M / 2))
    out = np.empty_like(x)
    for i in range(len(x)):
        out[i] = fn(x[max(0, i - half): i + half + 1])
    return out


def fit(x, y, i, j):
    """Least-squares line over [i, j] inclusive -> (slope, intercept, max abs error)."""
    xs, ys = x[i:j + 1], y[i:j + 1]
    if j - i < 1:
        return 0.0, ys[0], 0.0
    m, c = np.polyfit(xs, ys, 1)
    return m, c, float(np.max(np.abs(ys - (m * xs + c))))


def bottom_up(x, y, eps):
    """Start with 2-bin segments; repeatedly merge the adjacent pair whose merged
    line fit has the smallest max error, until no merge stays within eps."""
    bounds = list(range(0, len(x) - 1, 2)) + [len(x) - 1]
    segs = [[bounds[k], bounds[k + 1]] for k in range(len(bounds) - 1)]
    cost = [fit(x, y, segs[k][0], segs[k + 1][1])[2] for k in range(len(segs) - 1)]
    while cost:
        k = int(np.argmin(cost))
        if cost[k] > eps:
            break
        segs[k] = [segs[k][0], segs[k + 1][1]]
        del segs[k + 1]
        del cost[k]
        if k < len(cost):
            cost[k] = fit(x, y, segs[k][0], segs[k + 1][1])[2]
        if k > 0:
            cost[k - 1] = fit(x, y, segs[k - 1][0], segs[k][1])[2]
    return segs


def douglas_peucker(x, y, eps):
    """Top-down: split at the point of max vertical deviation from the chord."""
    out = []

    def rec(i, j):
        if j - i < 2:
            out.append([i, j])
            return
        chord = y[i] + (y[j] - y[i]) * (x[i:j + 1] - x[i]) / (x[j] - x[i])
        dev = np.abs(y[i:j + 1] - chord)
        k = int(np.argmax(dev))
        if dev[k] > eps:
            rec(i, i + k)
            rec(i + k, j)
        else:
            out.append([i, j])

    rec(0, len(x) - 1)
    return out


def chord_grade(x, y, s):
    i, j = s
    return 100.0 * (y[j] - y[i]) / (x[j] - x[i])


def significant(y, s):
    """A short hill runners would notice: not flat, and at least MIN_RISE_M of rise or drop."""
    i, j = s
    return abs(y[j] - y[i]) >= MIN_RISE_M


def tidy(x, y, segs):
    """Label, merge same-label neighbours, absorb short segments, repeat to a fixpoint."""
    segs = [list(s) for s in segs]
    while True:
        changed = False
        # merge adjacent same-label
        merged = [segs[0]]
        for s in segs[1:]:
            if label(chord_grade(x, y, merged[-1])) == label(chord_grade(x, y, s)):
                merged[-1] = [merged[-1][0], s[1]]
                changed = True
            else:
                merged.append(s)
        segs = merged
        # absorb the single shortest too-short segment into the neighbour whose grade is closer
        lengths = [x[j] - x[i] for i, j in segs]
        short = [k for k, L in enumerate(lengths) if L < MIN_SEG_M and not significant(y, segs[k])]
        if short and len(segs) > 1:
            k = min(short, key=lambda k: lengths[k])
            g = chord_grade(x, y, segs[k])
            cands = [n for n in (k - 1, k + 1) if 0 <= n < len(segs)]
            n = min(cands, key=lambda n: abs(chord_grade(x, y, segs[n]) - g))
            a, b = sorted((k, n))
            segs[a] = [segs[a][0], segs[b][1]]
            del segs[b]
            changed = True
        if not changed:
            return segs


def describe(x, y, segs):
    rows = []
    for i, j in segs:
        dy = np.diff(y[i:j + 1])
        # steepest 100 m inside the segment
        w = int(100 / STEP_M)
        if j - i >= w:
            g100 = 100.0 * (y[i + w:j + 1] - y[i:j + 1 - w]) / 100.0
            g = chord_grade(x, y, (i, j))
            steepest = float(g100.max() if g >= 0 else g100.min())
        else:
            steepest = chord_grade(x, y, (i, j))
        rows.append({
            "start_km": round(x[i] / 1000, 2),
            "end_km": round(x[j] / 1000, 2),
            "length_m": int(round(x[j] - x[i])),
            "elev_start_m": round(float(y[i]), 1),
            "elev_end_m": round(float(y[j]), 1),
            "gain_m": int(round(dy[dy > 0].sum())),
            "loss_m": int(round(-dy[dy < 0].sum())),
            "avg_grade_pct": round(chord_grade(x, y, (i, j)), 1),
            "steepest_100m_pct": round(steepest, 1),
            "type": label(chord_grade(x, y, (i, j))),
        })
    return rows


def main(path, out):
    s = json.load(open(path))
    x, raw = resample(s.get("distance_km", s.get("distance")), s.get("elevation_m", s.get("altitude")))
    y = rolling(rolling(raw, MEDIAN_M, np.median), MEAN_M, np.mean)

    results = {}
    for name, fn in (("bottom_up", bottom_up), ("douglas_peucker", douglas_peucker)):
        raw_segs = fn(x, y, EPS_M)
        segs = tidy(x, y, raw_segs)
        results[name] = {
            "raw_segment_count": len(raw_segs),
            "raw_breakpoints_km": [round(x[a] / 1000, 3) for a, _ in raw_segs] + [round(x[-1] / 1000, 3)],
            "segments": describe(x, y, segs),
        }
        print(f"{name}: {len(raw_segs)} raw -> {len(segs)} tidied")

    stride = 5  # 50 m plotting resolution
    payload = {
        "params": {"step_m": STEP_M, "median_m": MEDIAN_M, "mean_m": MEAN_M,
                   "eps_m": EPS_M, "min_seg_m": MIN_SEG_M, "min_rise_m": MIN_RISE_M,
                   "bands": [[hi if hi != float("inf") else None, n] for hi, n in BANDS]},
        "profile": [[round(x[k] / 1000, 3), round(float(y[k]), 1), round(float(raw[k]), 1)]
                    for k in range(0, len(x), stride)] + [[round(x[-1] / 1000, 3), round(float(y[-1]), 1), round(float(raw[-1]), 1)]],
        "results": results,
    }
    json.dump(payload, open(out, "w"))
    for r in results["bottom_up"]["segments"]:
        print(r)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
