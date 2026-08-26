from typing import Any

from rest_framework import serializers

from .models import Activity, ActivityComment, BestEffort, DurationCurve, Lap, Tag


class ActivitySerializer(serializers.ModelSerializer):
    tags = serializers.SerializerMethodField()
    child_activity_ids = serializers.SerializerMethodField()
    duplicate_activity_ids = serializers.SerializerMethodField()
    threshold_history = serializers.SerializerMethodField()

    class Meta:
        model = Activity
        fields = [
            "id",
            "athlete_id",
            "sport",
            "environment",
            "has_gps",
            "name",
            "start_date",
            "source",
            "device",
            "moving_time",
            "distance_km",
            "distance_source",
            "avg_power",
            "norm_power",
            "intensity",
            "tss",
            "avg_hr",
            "max_hr",
            "ascent",
            "max_power",
            "avg_cadence",
            "max_cadence",
            "max_speed",
            "total_descent",
            "elevation_min",
            "elevation_max",
            "calories",
            "trimp",
            "avg_left_balance_pct",
            "threshold_history",
            "start_weight_kg",
            "end_weight_kg",
            "fluids_ml",
            "avg_air_temp",
            "avg_humidity",
            "aerobic_training_effect",
            "anaerobic_training_effect",
            "training_effect_label",
            "tags",
            "workout_id",
            "bike_id",
            "shoe_id",
            "parent_activity_id",
            "child_activity_ids",
            "primary_activity_id",
            "duplicate_activity_ids",
        ]

    def get_tags(self, obj: Activity) -> list[str]:
        return list(obj.tags.order_by("name").values_list("name", flat=True))

    def get_child_activity_ids(self, obj: Activity) -> list[str]:
        if obj.sport != "multisport":
            return []
        return list(obj.child_activities.order_by("start_date").values_list("id", flat=True))

    def get_duplicate_activity_ids(self, obj: Activity) -> list[str]:
        # A duplicate can never itself be a primary (chains are rejected on update),
        # so skip the lookup for anything already linked to a primary.
        if obj.primary_activity_id:
            return []
        return list(obj.duplicate_activities.order_by("start_date").values_list("id", flat=True))

    def get_threshold_history(self, obj: Activity) -> list[dict]:
        # Every ThresholdHistory entry this activity is the source of - empty for the vast
        # majority of activities. is_current distinguishes "this is still the latest entry for
        # that field" from "since superseded by a later, different activity."
        from athletes.models import ThresholdHistory

        result = []
        for entry in obj.threshold_history.all():
            is_current = not ThresholdHistory.objects.filter(
                athlete_id=obj.athlete_id, field=entry.field, effective_from__gt=entry.effective_from
            ).exists()
            value = entry.value_pace if entry.field == "threshold_pace" else entry.value_numeric
            result.append({"field": entry.field, "value": value, "is_current": is_current})
        return result


class ActivityUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(required=False)
    sport = serializers.ChoiceField(choices=Activity.SPORT_CHOICES, required=False)
    workout_id = serializers.CharField(required=False, allow_null=True)
    primary_activity_id = serializers.CharField(required=False, allow_null=True)
    start_weight_kg = serializers.FloatField(required=False, allow_null=True)
    end_weight_kg = serializers.FloatField(required=False, allow_null=True)
    fluids_ml = serializers.IntegerField(required=False, allow_null=True)
    avg_air_temp = serializers.FloatField(required=False, allow_null=True)
    avg_humidity = serializers.IntegerField(required=False, allow_null=True)


class LapSerializer(serializers.ModelSerializer):
    class Meta:
        model = Lap
        fields = ["index", "duration", "distance_km", "avg_hr", "avg_power"]


class TagSerializer(serializers.ModelSerializer):
    # Populated via TagListView's `.annotate(count=Count("activity_tags"))`; falls back to
    # 0 for instances returned outside that queryset (e.g. the create/attach response).
    count = serializers.IntegerField(read_only=True, default=0)

    class Meta:
        model = Tag
        fields = ["id", "name", "origin", "color", "count"]
        read_only_fields = ["id", "origin"]


class DurationCurveSerializer(serializers.ModelSerializer):
    class Meta:
        model = DurationCurve
        fields = ["metric", "extends_to", "points"]


class BestEffortSerializer(serializers.ModelSerializer):
    activity_id = serializers.CharField(read_only=True)

    class Meta:
        model = BestEffort
        fields = ["window", "value", "unit", "date", "activity_id"]


class TagAttachSerializer(serializers.Serializer):
    tag_id = serializers.CharField(required=False)
    name = serializers.CharField(required=False)

    def validate(self, attrs: dict[str, Any]) -> dict[str, Any]:
        if not attrs.get("tag_id") and not attrs.get("name"):
            raise serializers.ValidationError("Provide either tag_id or name.")
        return attrs


class ActivityCommentSerializer(serializers.ModelSerializer):
    author_id = serializers.CharField(read_only=True)
    author_name = serializers.CharField(source="author.name", read_only=True)
    author_role = serializers.SerializerMethodField()

    class Meta:
        model = ActivityComment
        fields = ["id", "activity_id", "author_id", "author_name", "author_role", "parent_id", "text", "created"]

    def get_author_role(self, obj: ActivityComment) -> str:
        if obj.author_id == obj.activity.athlete_id:
            return "athlete"
        from accounts.models import UserRelationship

        relationship = UserRelationship.objects.filter(
            owner_id=obj.activity.athlete_id, grantee_id=obj.author_id, status=UserRelationship.STATUS_ACTIVE
        ).first()
        return relationship.role if relationship else "viewer"


class ActivityCommentCreateSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=4000, trim_whitespace=True)
    # Optional - omit for a top-level comment, or an existing top-level comment's id to reply
    # to it. See views.ActivityCommentListView.post for the single-level-threading rule this
    # enforces (belongs to the same activity, and isn't itself a reply).
    parent_id = serializers.CharField(required=False, allow_null=True)

    def validate_text(self, value: str) -> str:
        if not value.strip():
            raise serializers.ValidationError("Comment text cannot be empty.")
        return value
