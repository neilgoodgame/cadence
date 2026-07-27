"""Infers a WorkoutStep tree from an already-recorded Activity's laps.

Zwift (and most structured-workout-capable devices) auto-laps at every segment
change in a structured workout, so lap boundaries are usually a good proxy for
step boundaries. This turns that lap sequence into the same nested step-tree
shape `workouts.views._replace_steps`/`workouts.calculations` already consume,
optionally collapsing repeated contiguous patterns (e.g. 5x[work, rest]) into
`repeat` groups.

Pure/no side effects — nothing here touches the database beyond reading the
`Activity`/`Lap` rows and the athlete's FTP/LTHR. Returns a plain nested-dict
step tree; callers are responsible for persisting it (or not).
"""

from dataclasses import dataclass
from typing import Any

from activities.models import Activity, Lap
from athletes.zones import reference_for

# Tuning knobs — the things to retune once run against real activity history via
# `manage.py infer_workout_report`.
DURATION_TOLERANCE_FRACTION = 0.15
DURATION_TOLERANCE_MIN_SECONDS = 10
PCT_TOLERANCE = 5
MIN_REPEAT_COUNT = 2
MAX_COMPRESSION_PASSES = 4
WORK_THRESHOLD_PCT = 85


@dataclass
class LeafCandidate:
    """An intermediate leaf representation carrying enough info for both step-tree
    output and fuzzy repeat-pattern matching, before `kind` classification (which
    needs the full sequence, not just one lap) is applied.
    """

    duration: int
    target_type: str  # power | hr | open
    pct: float | None  # % FTP or % LTHR; None when target_type == "open"
    note: str = ""


@dataclass
class Group:
    repeat: int
    children: list["LeafCandidate | Group"]
    note: str = ""


def _leaf_from_lap(lap: Lap, ftp: int | None, lthr: int | None) -> LeafCandidate:
    if lap.avg_power is not None and ftp:
        return LeafCandidate(duration=lap.duration, target_type="power", pct=round(100 * lap.avg_power / ftp))
    if lap.avg_hr is not None and lthr:
        return LeafCandidate(duration=lap.duration, target_type="hr", pct=round(100 * lap.avg_hr / lthr))
    return LeafCandidate(duration=lap.duration, target_type="open", pct=None)


def _leaves_from_activity(activity: Activity) -> list[LeafCandidate]:
    zone_type = "bike_power" if activity.sport == "bike" else "run_power"
    ftp = reference_for(activity.athlete, zone_type)
    lthr = reference_for(activity.athlete, "heart_rate")
    return [_leaf_from_lap(lap, ftp, lthr) for lap in activity.laps.all()]


def _duration_tolerance(a: int, b: int) -> float:
    return max(DURATION_TOLERANCE_MIN_SECONDS, DURATION_TOLERANCE_FRACTION * max(a, b))


def _leaves_equivalent(a: LeafCandidate, b: LeafCandidate) -> bool:
    if a.target_type != b.target_type:
        return False
    if abs(a.duration - b.duration) > _duration_tolerance(a.duration, b.duration):
        return False
    if a.pct is None or b.pct is None:
        return a.pct == b.pct
    return abs(a.pct - b.pct) <= PCT_TOLERANCE


def _nodes_equivalent(a: "LeafCandidate | Group", b: "LeafCandidate | Group") -> bool:
    if isinstance(a, Group) and isinstance(b, Group):
        if a.repeat != b.repeat or len(a.children) != len(b.children):
            return False
        return all(_nodes_equivalent(x, y) for x, y in zip(a.children, b.children, strict=True))
    if isinstance(a, LeafCandidate) and isinstance(b, LeafCandidate):
        return _leaves_equivalent(a, b)
    return False


def _blocks_equivalent(a: list["LeafCandidate | Group"], b: list["LeafCandidate | Group"]) -> bool:
    return len(a) == len(b) and all(_nodes_equivalent(x, y) for x, y in zip(a, b, strict=True))


def _compress_pass(nodes: list["LeafCandidate | Group"]) -> list["LeafCandidate | Group"]:
    """One left-to-right greedy scan: at each position, find the (pattern length,
    repeat count) that covers the most nodes with the pattern repeating at least
    `MIN_REPEAT_COUNT` times, and collapse it into a Group. Ties on coverage favor
    the shorter pattern (a smaller, confidently-detected block over a longer,
    coincidental one).
    """
    n = len(nodes)
    result: list[LeafCandidate | Group] = []
    i = 0
    while i < n:
        best: tuple[int, int] | None = None  # (pattern_len, rep_count)
        max_pattern_len = (n - i) // MIN_REPEAT_COUNT
        for p in range(1, max_pattern_len + 1):
            block = nodes[i : i + p]
            k = 1
            j = i + p
            while j + p <= n and _blocks_equivalent(block, nodes[j : j + p]):
                k += 1
                j += p
            if k >= MIN_REPEAT_COUNT:
                covered = p * k
                if best is None or covered > best[0] * best[1] or (covered == best[0] * best[1] and p < best[0]):
                    best = (p, k)
        if best:
            p, k = best
            result.append(Group(repeat=k, children=nodes[i : i + p]))
            i += p * k
        else:
            result.append(nodes[i])
            i += 1
    return result


def _detect_repeats(leaves: list[LeafCandidate]) -> list["LeafCandidate | Group"]:
    nodes: list[LeafCandidate | Group] = list(leaves)
    for _ in range(MAX_COMPRESSION_PASSES):
        compressed = _compress_pass(nodes)
        if compressed == nodes or len(compressed) == len(nodes):
            return compressed
        nodes = compressed
    return nodes


def _classify_kind(leaf: LeafCandidate) -> str:
    """block/rec only - warmup/cool are a first/last-*top-level*-entry override
    applied afterwards in `infer_workout`, since a leaf's position in the original
    lap sequence doesn't survive repeat-group collapsing (a group's `children` is
    just the repeated pattern, not every original lap it was built from) and a
    repeat group's own children should never be classified warmup/cool anyway.
    """
    pct = leaf.pct if leaf.pct is not None else 100
    return "block" if pct >= WORK_THRESHOLD_PCT else "rec"


def _leaf_to_step(leaf: LeafCandidate) -> dict[str, Any]:
    return {
        "kind": _classify_kind(leaf),
        "end_type": "time",
        "duration": leaf.duration,
        "distance": None,
        "target_type": leaf.target_type,
        "target_low": leaf.pct,
        "target_high": leaf.pct,
        "target2_type": "none",
        "target2_low": None,
        "target2_high": None,
        "repeat": 1,
        "note": leaf.note,
    }


def _group_to_step(group: Group) -> dict[str, Any]:
    return {
        "kind": "repeat",
        "end_type": None,
        "duration": None,
        "distance": None,
        "target_type": None,
        "target_low": None,
        "target_high": None,
        "target2_type": "none",
        "target2_low": None,
        "target2_high": None,
        "repeat": group.repeat,
        "note": group.note,
        "children": [_node_to_step(child) for child in group.children],
    }


def _node_to_step(node: "LeafCandidate | Group") -> dict[str, Any]:
    return _group_to_step(node) if isinstance(node, Group) else _leaf_to_step(node)


def infer_workout(activity: Activity, *, auto_detect_repeats: bool) -> dict[str, Any]:
    """Returns `{"name", "sport", "steps"}` — a nested step tree ready to feed
    straight into the Builder (or `WorkoutCreateSerializer`) as an unsaved draft.
    Not persisted here.
    """
    leaves = _leaves_from_activity(activity)
    nodes: list[LeafCandidate | Group] = _detect_repeats(leaves) if auto_detect_repeats else list(leaves)
    steps = [_node_to_step(node) for node in nodes]

    # warmup/cool are a top-level-only override: the first/last *leaf* step (never
    # a repeat group, which can't sensibly be a warmup/cooldown) gets relabeled if
    # it looks like an easy lead-in/cooldown rather than a work interval.
    if steps and steps[0]["kind"] == "rec":
        steps[0]["kind"] = "warmup"
    if steps and steps[-1]["kind"] == "rec":
        steps[-1]["kind"] = "cool"

    return {"name": activity.name, "sport": activity.sport, "steps": steps}
