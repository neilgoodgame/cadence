"""Issue #501 (phase 4): reads/writes an athlete's saved MergeRules and decides, for a detected
overlap pair, which side (if either) plays target vs source under the applicable rule. Only
overlap_detection.resolve_pair/preview_resolution consume resolve()/assign() - everything else
here is the get_merge_rules/set_merge_rule MCP tools' backing logic. Mirrors backend_java's
MergeRuleService - keep the two in sync when changing either.
"""

from typing import NamedTuple

from rest_framework.exceptions import ValidationError

from .merge_apply import ACTIVITY_FIELDS, STREAM_FIELDS, NullPolicy
from .models import Activity, MergeRule

_VALID_FIELDS = set(STREAM_FIELDS) | set(ACTIVITY_FIELDS)

# No built-in default for a sport not listed here means automatic merging simply doesn't apply -
# there's no one obviously-right default to fall back to (see issue #501's own "Default
# selection by sport" - bike -> power, every other sport -> nothing pre-selected).
_BUILT_IN_DEFAULTS = {
    "bike": {
        "target_pattern": "device:zwift",
        "source_pattern": "device:garmin",
        "fields": ["power"],
        "null_policy": "keep_original",
    },
}


class ResolvedRule(NamedTuple):
    target_pattern: str
    source_pattern: str
    fields: list[str]
    null_policy: NullPolicy


class TargetSource(NamedTuple):
    target: Activity
    source: Activity


def resolve(athlete_id: str, sport: str) -> ResolvedRule | None:
    """The athlete's saved rule for `sport`, or its built-in default, or None if neither exists -
    meaning automatic merging doesn't apply for this sport/athlete."""
    saved = MergeRule.objects.filter(athlete_id=athlete_id, sport=sport).first()
    if saved is not None:
        return ResolvedRule(saved.target_pattern, saved.source_pattern, saved.fields, saved.null_policy)
    default = _BUILT_IN_DEFAULTS.get(sport)
    if default is None:
        return None
    return ResolvedRule(default["target_pattern"], default["source_pattern"], default["fields"], default["null_policy"])


def matches(activity: Activity, pattern: str | None) -> bool:
    """`pattern` is "device:<prefix>" - a case-insensitive prefix match against Activity.device.
    Only device: patterns exist today."""
    if not pattern or ":" not in pattern:
        return False
    kind, _, prefix = pattern.partition(":")
    if kind != "device":
        return False
    device = activity.device
    return bool(device) and device.lower().startswith(prefix.lower())


def assign(a: Activity, b: Activity, rule: ResolvedRule) -> TargetSource | None:
    """Which of a/b plays target vs source under `rule` - order-independent, same spirit as
    overlap_detection.choose_primary. None if neither orientation matches (e.g. both activities
    are Zwift, or neither matches the rule's patterns at all) - the caller falls back to
    flagging rather than guessing."""
    if matches(a, rule.target_pattern) and matches(b, rule.source_pattern):
        return TargetSource(a, b)
    if matches(b, rule.target_pattern) and matches(a, rule.source_pattern):
        return TargetSource(b, a)
    return None


def list_rules(athlete_id: str) -> list[MergeRule]:
    return list(MergeRule.objects.filter(athlete_id=athlete_id).order_by("sport"))


def _validate_pattern(pattern: str | None, param: str) -> None:
    if not pattern or not pattern.startswith("device:") or len(pattern) <= len("device:"):
        raise ValidationError({param: "Must be 'device:<prefix>', e.g. 'device:zwift'."})


def set_rule(
    athlete_id: str, sport: str, target_pattern: str, source_pattern: str, fields: list[str], null_policy: NullPolicy
) -> MergeRule:
    _validate_pattern(target_pattern, "target_pattern")
    _validate_pattern(source_pattern, "source_pattern")
    if not fields:
        raise ValidationError({"fields": "At least one field must be selected."})
    unknown = [f for f in fields if f not in _VALID_FIELDS]
    if unknown:
        raise ValidationError({"fields": f"Unknown field(s): {', '.join(unknown)}."})

    rule, _created = MergeRule.objects.update_or_create(
        athlete_id=athlete_id,
        sport=sport,
        defaults={
            "target_pattern": target_pattern,
            "source_pattern": source_pattern,
            "fields": fields,
            "null_policy": null_policy,
        },
    )
    return rule
