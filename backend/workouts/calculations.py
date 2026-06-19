def compute_duration_and_tss(steps):
    """Recompute a workout's total duration (seconds) and TSS from its step list.

    `steps` is a list of dicts with keys `end_type`/`duration`/`target_pct`/`repeat`.
    Only `time` steps with a `duration` contribute — `distance`/`manual` steps are
    excluded because we have no pace assumption to convert distance to time without
    real activity history. This is an intentional simplification, not a bug.
    """
    duration_seconds = 0
    tss = 0.0
    for step in steps:
        repeat = step.get("repeat") or 1
        if step["end_type"] == "time" and step.get("duration"):
            step_duration = step["duration"] * repeat
            duration_seconds += step_duration
            target_pct = step.get("target_pct") or 0
            tss += (step_duration / 3600) * (target_pct / 100) ** 2 * 100
    return duration_seconds, round(tss)
