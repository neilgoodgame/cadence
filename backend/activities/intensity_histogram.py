"""Per-activity power/heart-rate histograms for the threshold-decay load term (issue #485,
docs/plans/threshold-decay.md section 2.3) - how many seconds an activity spent at each whole
watt of 30s-smoothed power, and at each whole bpm of heart rate. Stored once per activity
(ActivityIntensityHistogram) so the decay load calculation never needs to re-read raw Record
streams - millions of rows for a long-established account, against a 600MB container on staging.

Mirrors activities/elevation.py's "own table, no row yet means it hasn't been built, lazy-build
on first read" pattern exactly - not something to redesign here.
"""

from __future__ import annotations

from collections.abc import Sequence

from .models import Activity, ActivityIntensityHistogram

# Same fixed, index-based (not time-gap-aware) window uploads.processing.compute_normalized_power
# already uses - the two share this helper so NP and the histogram agree on what "30s-smoothed"
# means, rather than drifting into two independently-tuned definitions of the same thing.
ROLLING_WINDOW = 30


def rolling_mean_power(power_series: Sequence[float | None], window: int = ROLLING_WINDOW) -> list[float]:
    """The 30s-smoothed power series both NP (uploads.processing.compute_normalized_power) and
    the histogram below need - zero-fills a missing sample, same as NP always has. Downsampled to
    one value per window position once the series is long enough (`len - window + 1` points), or
    a single whole-activity average if it's shorter than `window` - also matching NP's own
    short-activity fallback."""
    values = [p if p is not None else 0 for p in power_series]
    if not values:
        return []
    if len(values) < window:
        return [sum(values) / len(values)]
    rolling: list[float] = []
    window_sum = sum(values[:window])
    rolling.append(window_sum / window)
    for i in range(window, len(values)):
        window_sum += values[i] - values[i - window]
        rolling.append(window_sum / window)
    return rolling


def _tally(values: Sequence[float | None], rounder=round) -> dict[int, int] | None:
    """Seconds spent at each rounded value - each entry assumed to cover one second, the same
    ~1Hz assumption compute_normalized_power already makes (no new assumption introduced here).
    None with nothing to tally (every sample missing), matching the nullable histogram columns."""
    counts: dict[int, int] = {}
    for v in values:
        if v is None:
            continue
        key = int(rounder(v))
        counts[key] = counts.get(key, 0) + 1
    return counts or None


def build_histogram(
    power_series: Sequence[float | None], hr_series: Sequence[float | None]
) -> tuple[dict[int, int] | None, dict[int, int] | None]:
    """Pure: `power_series`/`hr_series` -> `(power_30s_histogram, heartrate_histogram)`. The
    caller is responsible for any gating (e.g. a running-power-source exclusion) - this function
    just tallies whatever series it's given. Power is smoothed first (via rolling_mean_power);
    heart rate is tallied raw - the design doc only calls for smoothing on the power side."""
    power_hist = _tally(rolling_mean_power(power_series)) if any(p is not None for p in power_series) else None
    hr_hist = _tally(hr_series)
    return power_hist, hr_hist


def rebuild_histogram(
    activity: Activity,
    power_series: Sequence[float | None] | None = None,
    hr_series: Sequence[float | None] | None = None,
) -> ActivityIntensityHistogram:
    """(Re)builds and saves the activity's intensity histogram. `power_series`/`hr_series` let a
    caller that already built these series locally (ingest, backfill_extended_stats) pass them in
    directly, already gated for running-power-source exclusion if applicable, rather than this
    function re-querying Records; omitted, they're fetched (and gated) here - the ensure_histogram
    lazy-backfill path, which has no pre-built series at hand."""
    if power_series is None or hr_series is None:
        rows = list(activity.records.order_by("t").values_list("power", "heartrate"))
        if power_series is None:
            power_series = [r[0] for r in rows]
            if activity.sport == "run" and not activity.matches_running_power_preference(activity.athlete):
                # Same gate every other compute_normalized_power caller already applies before
                # the call - this path has no external caller to do it, so it does it itself.
                power_series = [None] * len(power_series)
        if hr_series is None:
            hr_series = [r[1] for r in rows]
    power_hist, hr_hist = build_histogram(power_series, hr_series)
    histogram, _ = ActivityIntensityHistogram.objects.update_or_create(
        activity=activity, defaults={"power_30s": power_hist, "heartrate": hr_hist}
    )
    return histogram


def ensure_histogram(activity: Activity) -> ActivityIntensityHistogram:
    """The stored histogram, built on first read."""
    histogram = ActivityIntensityHistogram.objects.filter(activity=activity).first()
    return histogram if histogram is not None else rebuild_histogram(activity)
