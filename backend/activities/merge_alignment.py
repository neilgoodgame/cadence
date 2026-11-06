"""Issue #501 (phase 1): computes the time offset and alignment confidence between two
activities suspected of being the same ride recorded twice (see #498's own duplicate detection) -
the foundation everything else in #501 builds on (preview, merge, automatic merging). Pure
computation only: no database access, no persistence - callers fetch the two activities' Record
streams and the start-date delta, and pass them in. Mirrors backend_java's
ActivityMergeAlignmentCalculator - keep the two in sync when changing either.
"""

from collections.abc import Sequence
from dataclasses import dataclass

# Below this many overlapping (non-null, paired) samples, a correlation score isn't trustworthy
# enough to report for that candidate offset - the pair is skipped rather than scored near-zero.
MIN_OVERLAP_SAMPLES = 30
# How far past the initial guess (start-date delta) to search, in both directions - observed
# real-world offsets for Zwift/Garmin pairs of the same ride run from a few seconds to ~55 s
# (see issue #501), so +/-120 s comfortably covers it with margin.
SEARCH_RADIUS_S = 120

RecordRow = tuple[int, int | None, int | None]  # (t, cadence, power)


@dataclass(frozen=True)
class AlignmentResult:
    offset_seconds: int
    score: float
    signal: str | None  # "cadence" | "power" | None (no usable signal found at all)
    overlap_seconds: int  # the winning offset's matched-sample count


def _pearson(pairs: Sequence[tuple[float, float]]) -> float:
    n = len(pairs)
    sum_x = sum(x for x, _ in pairs)
    sum_y = sum(y for _, y in pairs)
    sum_xy = sum(x * y for x, y in pairs)
    sum_x2 = sum(x * x for x, _ in pairs)
    sum_y2 = sum(y * y for _, y in pairs)
    denom = ((n * sum_x2 - sum_x**2) * (n * sum_y2 - sum_y**2)) ** 0.5
    return (n * sum_xy - sum_x * sum_y) / denom if denom else 0.0


def _best_offset(primary: dict[int, int], secondary: dict[int, int], initial_offset: int) -> tuple[int, float, int]:
    """Searches candidate offsets around ``initial_offset``; for each, pairs up primary[t] with
    secondary[t - offset] wherever both exist, and scores the pairing by Pearson correlation -
    offset is how much later (in seconds) the secondary activity started than the primary, so
    primary's local time t corresponds to secondary's local time (t - offset). Returns
    (best_offset, best_score, best_overlap_seconds); best_overlap_seconds is 0 if no candidate
    offset had enough overlap to score at all, in which case best_offset falls back to
    initial_offset and best_score to 0.0."""
    best_offset, best_score, best_overlap = initial_offset, 0.0, 0
    for offset in range(initial_offset - SEARCH_RADIUS_S, initial_offset + SEARCH_RADIUS_S + 1):
        pairs = [(primary[t], secondary[t - offset]) for t in primary if (t - offset) in secondary]
        if len(pairs) < MIN_OVERLAP_SAMPLES:
            continue
        score = _pearson(pairs)
        if score > best_score or (score == best_score and len(pairs) > best_overlap):
            best_offset, best_score, best_overlap = offset, score, len(pairs)
    return best_offset, best_score, best_overlap


def align(
    primary_records: Sequence[RecordRow],
    secondary_records: Sequence[RecordRow],
    initial_offset_seconds: int,
) -> AlignmentResult:
    """``primary_records``/``secondary_records``: each activity's own (t, cadence, power) rows,
    in any order - a later row for a repeated t silently wins. ``initial_offset_seconds`` is
    ``secondary.start_date - primary.start_date`` in whole seconds, the caller's own one-line
    computation - kept out of this function so it stays pure and testable without datetimes.

    Tries cadence first - the more device-independent signal (Kickr vs. pedal power read
    meaningfully differently for the same effort, see #499; cadence doesn't) - only falling back
    to power when either side has no usable cadence samples at all."""
    primary_cadence = {t: c for t, c, _ in primary_records if c is not None}
    secondary_cadence = {t: c for t, c, _ in secondary_records if c is not None}
    if primary_cadence and secondary_cadence:
        offset, score, overlap = _best_offset(primary_cadence, secondary_cadence, initial_offset_seconds)
        if overlap:
            return AlignmentResult(offset, score, "cadence", overlap)

    primary_power = {t: p for t, _, p in primary_records if p is not None}
    secondary_power = {t: p for t, _, p in secondary_records if p is not None}
    if primary_power and secondary_power:
        offset, score, overlap = _best_offset(primary_power, secondary_power, initial_offset_seconds)
        if overlap:
            return AlignmentResult(offset, score, "power", overlap)

    return AlignmentResult(initial_offset_seconds, 0.0, None, 0)
