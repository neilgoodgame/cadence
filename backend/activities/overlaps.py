"""Issue #498's overlap-detection backbone: finds pairs of an athlete's activities whose
recorded intervals intersect - the same ride uploaded twice from two devices (Zwift + Garmin),
but also any genuinely overlapping pair. Powers `GET /v1/activities/overlaps`, this issue's
backfill, #501's merge candidate list, and the MCP `list_overlapping_activities` tool.

Raw SQL via `connection.cursor()`, not the ORM - the join condition is Postgres's `tstzrange`
overlap operator (`&&`), which the ORM has no expression for, and the self-join plus computed
per-pair metrics (overlap_pct, duration_delta_pct, avg_power_ratio) would be unreadable as a
queryset. Mirrors `backend_java`'s `ActivityOverlapRepository` - see its docstring for the design
rationale (duration fallback, anchor/other labelling, dedup-across-asymmetric-filters, the
NULL-safe `IS DISTINCT FROM` duplicate-link check) rather than repeating it here; keep the two in
sync when changing either.
"""

import base64
from datetime import UTC, date, datetime, timedelta

from django.db import connection
from rest_framework.exceptions import ValidationError

VALID_OTHER_HAS = {"power", "heartrate", "cadence", "core_temp"}
DEFAULT_MIN_OVERLAP_PCT = 50.0

_SQL = """
WITH anchor_act AS (
  SELECT id, sport, device, start_date, name, avg_power, primary_activity_id,
         COALESCE(elapsed_seconds, moving_time) AS duration_s
  FROM activities_activity
  WHERE athlete_id = %(athlete_id)s
    AND (CAST(%(activity_id)s AS text) IS NULL OR id = %(activity_id)s)
    AND (CAST(%(sport)s AS text) IS NULL OR sport = %(sport)s)
    AND (CAST(%(device)s AS text) IS NULL OR device ILIKE %(device)s || '%%')
    AND (CAST(%(environment)s AS text) IS NULL OR environment = %(environment)s)
    AND (CAST(%(from_dt)s AS timestamptz) IS NULL OR start_date >= %(from_dt)s)
    AND (CAST(%(to_dt_exclusive)s AS timestamptz) IS NULL OR start_date < %(to_dt_exclusive)s)
    AND (CAST(%(min_duration_seconds)s AS integer) IS NULL
         OR COALESCE(elapsed_seconds, moving_time) >= %(min_duration_seconds)s)
),
other_act AS (
  SELECT id, sport, device, start_date, name, avg_power, avg_hr, avg_cadence, avg_core_temp,
         primary_activity_id, COALESCE(elapsed_seconds, moving_time) AS duration_s
  FROM activities_activity
  WHERE athlete_id = %(athlete_id)s
    AND (CAST(%(sport)s AS text) IS NULL OR %(cross_sport)s OR sport = %(sport)s)
    AND (CAST(%(other_device)s AS text) IS NULL OR device ILIKE %(other_device)s || '%%')
    AND (CAST(%(environment)s AS text) IS NULL OR environment = %(environment)s)
    AND (CAST(%(min_duration_seconds)s AS integer) IS NULL
         OR COALESCE(elapsed_seconds, moving_time) >= %(min_duration_seconds)s)
    AND (NOT %(require_other_power)s OR avg_power IS NOT NULL)
    AND (NOT %(require_other_heartrate)s OR avg_hr IS NOT NULL)
    AND (NOT %(require_other_cadence)s OR avg_cadence IS NOT NULL)
    AND (NOT %(require_other_core_temp)s OR avg_core_temp IS NOT NULL)
),
pairs AS (
  SELECT
    a.id AS anchor_id, a.name AS anchor_name, a.sport AS anchor_sport, a.device AS anchor_device,
    a.start_date AS anchor_start_date, a.duration_s AS anchor_elapsed_seconds, a.avg_power AS anchor_avg_power,
    b.id AS other_id, b.name AS other_name, b.sport AS other_sport, b.device AS other_device,
    b.start_date AS other_start_date, b.duration_s AS other_elapsed_seconds, b.avg_power AS other_avg_power,
    EXTRACT(epoch FROM (
      LEAST(a.start_date + a.duration_s * interval '1 second', b.start_date + b.duration_s * interval '1 second')
      - GREATEST(a.start_date, b.start_date)
    ))::bigint AS overlap_seconds,
    (EXTRACT(epoch FROM (
      LEAST(a.start_date + a.duration_s * interval '1 second', b.start_date + b.duration_s * interval '1 second')
      - GREATEST(a.start_date, b.start_date)
    )) / NULLIF(LEAST(a.duration_s, b.duration_s), 0) * 100)::float8 AS overlap_pct,
    ABS(EXTRACT(epoch FROM (a.start_date - b.start_date)))::bigint AS start_delta_seconds,
    (ABS(a.duration_s - b.duration_s)::float8 / NULLIF(a.duration_s, 0) * 100) AS duration_delta_pct,
    (b.avg_power::float8 / NULLIF(a.avg_power, 0)) AS avg_power_ratio,
    CASE WHEN b.primary_activity_id IS NOT DISTINCT FROM a.id
         OR a.primary_activity_id IS NOT DISTINCT FROM b.id
         THEN 'duplicate' ELSE 'unresolved' END AS status
  FROM anchor_act a
  JOIN other_act b ON b.id <> a.id
    AND tstzrange(a.start_date, a.start_date + a.duration_s * interval '1 second')
        && tstzrange(b.start_date, b.start_date + b.duration_s * interval '1 second')
  WHERE %(include_resolved)s
     OR (b.primary_activity_id IS DISTINCT FROM a.id
         AND a.primary_activity_id IS DISTINCT FROM b.id)
),
deduped AS (
  -- Collapses the unordered pair's two directed rows (anchor/other swapped) into one,
  -- canonically keeping the lower-id side as anchor - but only when BOTH directions
  -- actually survived the CTEs above. An asymmetric filter (cross_sport, device vs
  -- other_device, other_has) can make only ONE direction valid at all, in which case
  -- there's nothing to collapse and that direction is kept regardless of id order.
  SELECT DISTINCT ON (LEAST(anchor_id, other_id), GREATEST(anchor_id, other_id)) *
  FROM pairs
  ORDER BY LEAST(anchor_id, other_id), GREATEST(anchor_id, other_id), anchor_id
)
SELECT * FROM deduped
WHERE overlap_pct >= %(min_overlap_pct)s
  AND (CAST(%(max_start_delta_seconds)s AS bigint) IS NULL
       OR start_delta_seconds <= %(max_start_delta_seconds)s)
  AND (CAST(%(cursor_anchor_id)s AS text) IS NULL
       OR (anchor_start_date, anchor_id, other_id)
          > (CAST(%(cursor_start)s AS timestamptz), %(cursor_anchor_id)s, %(cursor_other_id)s))
ORDER BY anchor_start_date, anchor_id, other_id
LIMIT %(limit_plus_one)s
"""

_COLUMNS = [
    "anchor_id",
    "anchor_name",
    "anchor_sport",
    "anchor_device",
    "anchor_start_date",
    "anchor_elapsed_seconds",
    "anchor_avg_power",
    "other_id",
    "other_name",
    "other_sport",
    "other_device",
    "other_start_date",
    "other_elapsed_seconds",
    "other_avg_power",
    "overlap_seconds",
    "overlap_pct",
    "start_delta_seconds",
    "duration_delta_pct",
    "avg_power_ratio",
    "status",
]


def _decode_cursor(cursor: str | None) -> tuple[datetime | None, str | None, str | None]:
    if cursor is None:
        return None, None, None
    try:
        raw = base64.urlsafe_b64decode(cursor.encode()).decode()
        epoch_millis_s, anchor_id, other_id = raw.split(":", 2)
        start_date = datetime.fromtimestamp(int(epoch_millis_s) / 1000, tz=UTC)
        return start_date, anchor_id, other_id
    except (ValueError, UnicodeDecodeError) as exc:
        raise ValidationError({"cursor": "Invalid cursor."}) from exc


def _encode_cursor(row: dict) -> str:
    epoch_millis = int(row["anchor_start_date"].timestamp() * 1000)
    raw = f"{epoch_millis}:{row['anchor_id']}:{row['other_id']}"
    return base64.urlsafe_b64encode(raw.encode()).decode().rstrip("=")


def _activity_summary(row: dict, prefix: str) -> dict:
    return {
        "id": row[f"{prefix}_id"],
        "name": row[f"{prefix}_name"],
        "sport": row[f"{prefix}_sport"],
        "device": row[f"{prefix}_device"],
        "start_date": row[f"{prefix}_start_date"],
        "elapsed_seconds": row[f"{prefix}_elapsed_seconds"],
        "avg_power": row[f"{prefix}_avg_power"],
    }


def _to_pair(row: dict) -> dict:
    return {
        "anchor": _activity_summary(row, "anchor"),
        "other": _activity_summary(row, "other"),
        "overlap_seconds": row["overlap_seconds"],
        "overlap_pct": row["overlap_pct"],
        "start_delta_seconds": row["start_delta_seconds"],
        "duration_delta_pct": row["duration_delta_pct"],
        "avg_power_ratio": row["avg_power_ratio"],
        "status": row["status"],
    }


def find_overlaps(
    athlete_id: str,
    *,
    activity_id: str | None = None,
    sport: str | None = None,
    cross_sport: bool = False,
    device: str | None = None,
    other_device: str | None = None,
    environment: str | None = None,
    from_date: date | None = None,
    to_date: date | None = None,
    min_duration_seconds: int | None = None,
    other_has: list[str] | None = None,
    min_overlap_pct: float | None = None,
    max_start_delta_seconds: int | None = None,
    include_resolved: bool = False,
    cursor: str | None = None,
    limit: int = 50,
) -> dict:
    other_has = other_has or []
    for has in other_has:
        if has not in VALID_OTHER_HAS:
            raise ValidationError({"otherHas": f'otherHas must be one of {sorted(VALID_OTHER_HAS)}, got "{has}".'})

    from_dt = datetime.combine(from_date, datetime.min.time(), tzinfo=UTC) if from_date else None
    # Inclusive of `to_date` itself - one day past midnight makes the upper bound exclusive.
    to_dt_exclusive = (
        datetime.combine(to_date + timedelta(days=1), datetime.min.time(), tzinfo=UTC) if to_date else None
    )
    cursor_start, cursor_anchor_id, cursor_other_id = _decode_cursor(cursor)

    params = {
        "athlete_id": athlete_id,
        "activity_id": activity_id,
        "sport": sport,
        "cross_sport": cross_sport,
        "device": device,
        "other_device": other_device,
        "environment": environment,
        "from_dt": from_dt,
        "to_dt_exclusive": to_dt_exclusive,
        "min_duration_seconds": min_duration_seconds,
        "require_other_power": "power" in other_has,
        "require_other_heartrate": "heartrate" in other_has,
        "require_other_cadence": "cadence" in other_has,
        "require_other_core_temp": "core_temp" in other_has,
        "include_resolved": include_resolved,
        "min_overlap_pct": min_overlap_pct if min_overlap_pct is not None else DEFAULT_MIN_OVERLAP_PCT,
        "max_start_delta_seconds": max_start_delta_seconds,
        "cursor_start": cursor_start,
        "cursor_anchor_id": cursor_anchor_id,
        "cursor_other_id": cursor_other_id,
        "limit_plus_one": limit + 1,
    }

    with connection.cursor() as cur:
        cur.execute(_SQL, params)
        rows = [dict(zip(_COLUMNS, row, strict=True)) for row in cur.fetchall()]

    has_more = len(rows) > limit
    page = rows[:limit] if has_more else rows
    next_cursor = _encode_cursor(page[-1]) if has_more else None
    return {
        "object": "list",
        "has_more": has_more,
        "next_cursor": next_cursor,
        "data": [_to_pair(row) for row in page],
    }
