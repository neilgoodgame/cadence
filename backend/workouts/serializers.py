from typing import Any

from rest_framework import serializers

from .models import Workout, WorkoutStep

LEAF_KINDS = {"warmup", "block", "rec", "cool"}


def _clean_leaf(step: dict[str, Any]) -> dict[str, Any]:
    end_type = step.get("end_type")
    if end_type not in dict(WorkoutStep.END_TYPE_CHOICES):
        raise serializers.ValidationError({"end_type": "end_type must be one of time, distance, manual."})
    if end_type == "time" and not step.get("duration"):
        raise serializers.ValidationError({"duration": "duration is required when end_type is 'time'."})
    if end_type == "distance" and not step.get("distance"):
        raise serializers.ValidationError({"distance": "distance is required when end_type is 'distance'."})

    target_type = step.get("target_type")
    if target_type not in dict(WorkoutStep.TARGET_TYPE_CHOICES):
        raise serializers.ValidationError({"target_type": "target_type must be one of power, hr, pace, cadence, open."})

    target2_type = step.get("target2_type") or "none"
    if target2_type not in dict(WorkoutStep.TARGET2_TYPE_CHOICES):
        raise serializers.ValidationError({"target2_type": "target2_type must be one of cadence, none."})

    return {
        "kind": step["kind"],
        "end_type": end_type,
        "duration": step.get("duration"),
        "distance": step.get("distance"),
        "target_type": target_type,
        "target_low": step.get("target_low"),
        "target_high": step.get("target_high"),
        "target2_type": target2_type,
        "target2_low": step.get("target2_low"),
        "target2_high": step.get("target2_high"),
        "repeat": 1,
        "note": step.get("note") or "",
        "children": [],
    }


def _clean_group(step: dict[str, Any]) -> dict[str, Any]:
    children = step.get("children") or []
    if not children:
        raise serializers.ValidationError({"children": "A repeat group must have at least one child step."})
    return {
        "kind": "repeat",
        "end_type": "",
        "duration": None,
        "distance": None,
        "target_type": "",
        "target_low": None,
        "target_high": None,
        "target2_type": "none",
        "target2_low": None,
        "target2_high": None,
        "repeat": step.get("repeat") or 1,
        "note": step.get("note") or "",
        "children": [clean_step(child) for child in children],
    }


def clean_step(step: dict[str, Any]) -> dict[str, Any]:
    kind = step.get("kind")
    if kind not in dict(WorkoutStep.KIND_CHOICES):
        raise serializers.ValidationError({"kind": "kind must be one of warmup, block, rec, cool, repeat."})
    if kind == "repeat":
        return _clean_group(step)
    return _clean_leaf(step)


def clean_step_tree(steps: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Validate and normalize a client-submitted nested step tree into the
    shape `_create_steps`/`compute_duration_and_tss` expect. Raises
    `rest_framework.exceptions.ValidationError` (caught by DRF's default
    exception handler) on any structural violation.
    """
    return [clean_step(step) for step in steps]


def build_step_tree(workout: Workout) -> list[dict[str, Any]]:
    """Inverse of `clean_step_tree`/`_create_steps`: reconstructs the nested
    `children` shape from the flat `parent`-linked rows for API responses.
    """
    by_parent: dict[int | None, list[WorkoutStep]] = {}
    for step in workout.steps.all():
        by_parent.setdefault(step.parent_id, []).append(step)

    def build(parent_id: int | None) -> list[dict[str, Any]]:
        return [
            {
                "kind": s.kind,
                "end_type": s.end_type,
                "duration": s.duration,
                "distance": s.distance,
                "target_type": s.target_type,
                "target_low": s.target_low,
                "target_high": s.target_high,
                "target2_type": s.target2_type,
                "target2_low": s.target2_low,
                "target2_high": s.target2_high,
                "repeat": s.repeat,
                "note": s.note,
                "children": build(s.id) if s.kind == "repeat" else [],
            }
            for s in by_parent.get(parent_id, [])
        ]

    return build(None)


class WorkoutStepSerializer(serializers.Serializer):
    """Read-side only — serializes the nested dict tree built by
    `build_step_tree`. Input validation goes through `clean_step_tree` instead,
    since a `repeat` group and a leaf step have entirely different required
    fields and DRF's `ModelSerializer` doesn't nest a serializer into itself.
    """

    kind = serializers.CharField()
    end_type = serializers.CharField(allow_null=True)
    duration = serializers.IntegerField(allow_null=True)
    distance = serializers.IntegerField(allow_null=True)
    target_type = serializers.CharField(allow_null=True)
    target_low = serializers.FloatField(allow_null=True)
    target_high = serializers.FloatField(allow_null=True)
    target2_type = serializers.CharField()
    target2_low = serializers.FloatField(allow_null=True)
    target2_high = serializers.FloatField(allow_null=True)
    repeat = serializers.IntegerField()
    note = serializers.CharField()
    children = serializers.SerializerMethodField()

    def get_children(self, obj: dict[str, Any]) -> list[dict[str, Any]]:
        return WorkoutStepSerializer(obj.get("children") or [], many=True).data


class WorkoutSerializer(serializers.ModelSerializer):
    class Meta:
        model = Workout
        fields = ["id", "name", "sport", "type", "duration", "tss"]
        read_only_fields = ["id", "type", "duration", "tss"]


class WorkoutDetailSerializer(WorkoutSerializer):
    steps = serializers.SerializerMethodField()

    class Meta(WorkoutSerializer.Meta):
        fields = WorkoutSerializer.Meta.fields + ["steps"]

    def get_steps(self, workout: Workout) -> list[dict[str, Any]]:
        return WorkoutStepSerializer(build_step_tree(workout), many=True).data


class WorkoutCreateSerializer(serializers.Serializer):
    name = serializers.CharField()
    sport = serializers.ChoiceField(choices=Workout.SPORT_CHOICES)
    steps = serializers.ListField(child=serializers.DictField())


class WorkoutUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(required=False)
    steps = serializers.ListField(child=serializers.DictField(), required=False)


class WorkoutMatchSerializer(serializers.Serializer):
    activity_id = serializers.CharField()
    name = serializers.CharField()
    date = serializers.DateField()
    method = serializers.ChoiceField(choices=["auto", "manual"])
    confidence = serializers.FloatField(allow_null=True)
    compliance = serializers.FloatField(allow_null=True)
