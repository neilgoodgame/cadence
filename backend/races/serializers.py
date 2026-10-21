from rest_framework import serializers

from courses.elevation import ensure_elevation_series
from courses.serializers import CourseSerializer

from .models import Race, RaceComment


class RaceSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)
    parent_race_id = serializers.SerializerMethodField()
    block_start = serializers.SerializerMethodField()
    block_weeks = serializers.SerializerMethodField()

    class Meta:
        model = Race
        fields = [
            "id",
            "athlete_id",
            "name",
            "date",
            "sport",
            "priority",
            "course_id",
            "distance_km",
            "goal_time",
            "result_time",
            "target_power",
            "activity_id",
            "url",
            "results_url",
            "notes",
            "key_session_tag_id",
            "parent_race_id",
            "block_start",
            "block_weeks",
            "is_public",
        ]
        read_only_fields = ["id", "athlete_id"]

    def _block_info(self, race: Race) -> dict | None:
        from .blocks import a_race_block_range, find_parent_a_race

        cache = self.context.setdefault("_block_cache", {})
        if race.id in cache:
            return cache[race.id]

        block_weeks_pref = race.athlete.race_block_weeks
        if race.priority == "A":
            info = a_race_block_range(race, block_weeks_pref)
        else:
            parent = find_parent_a_race(race, block_weeks_pref)
            info = a_race_block_range(parent, block_weeks_pref) if parent else None
        cache[race.id] = info
        return info

    def get_parent_race_id(self, race: Race) -> str | None:
        if race.priority == "A":
            return None
        from .blocks import find_parent_a_race

        parent = find_parent_a_race(race, race.athlete.race_block_weeks)
        return parent.id if parent else None

    def get_block_start(self, race: Race) -> str | None:
        info = self._block_info(race)
        return info["start_week"].isoformat() if info else None

    def get_block_weeks(self, race: Race) -> int | None:
        info = self._block_info(race)
        return info["weeks"] if info else None


class RaceCreateSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)
    # ModelSerializer auto-builds any <relation>_id-named field as a ReadOnlyField (it
    # expects the relation's own name, e.g. "activity", as the writable one) - declared
    # explicitly here so create() actually picks these up from the request body. Race.objects
    # .create(**validated_data) accepts the plain _id kwarg directly, same as the model itself.
    activity_id = serializers.CharField(required=False, allow_null=True)
    course_id = serializers.CharField(required=False, allow_null=True)
    key_session_tag_id = serializers.CharField(required=False, allow_null=True)

    class Meta:
        model = Race
        fields = [
            "name",
            "date",
            "sport",
            "priority",
            "course_id",
            "distance_km",
            "goal_time",
            "result_time",
            "target_power",
            "activity_id",
            "url",
            "results_url",
            "notes",
            "key_session_tag_id",
        ]
        extra_kwargs = {field: {"required": False} for field in fields if field not in ("name", "date")}


class RaceUpdateSerializer(serializers.ModelSerializer):
    goal_time = serializers.DurationField(allow_null=True, required=False)
    result_time = serializers.DurationField(allow_null=True, required=False)
    activity_id = serializers.CharField(required=False, allow_null=True)
    course_id = serializers.CharField(required=False, allow_null=True)
    key_session_tag_id = serializers.CharField(required=False, allow_null=True)

    class Meta:
        model = Race
        fields = [
            "name",
            "date",
            "sport",
            "priority",
            "course_id",
            "distance_km",
            "goal_time",
            "result_time",
            "target_power",
            "activity_id",
            "url",
            "results_url",
            "notes",
            "key_session_tag_id",
            "is_public",
        ]
        extra_kwargs = {field: {"required": False} for field in fields}


# The course fields a public race exposes - the course's shape and elevation, nothing about who
# made it (athlete_id, source activity, GPX filename) or their other races on it.
PUBLIC_COURSE_FIELDS = (
    "name",
    "location_name",
    "distance_km",
    "elevation_gain_m",
    "elevation_loss_m",
    "elevation_max_m",
    "elevation_min_m",
    "is_loop",
    "route_polyline",
    "elevation_profile",
    "elevation_quality",
    "sections",
)


class PublicRaceSerializer(serializers.ModelSerializer):
    """GET /v1/public/races/<id> - the unauthenticated, read-only view of a race its owner has
    marked public, for sharing a result with friends and family. Deliberately a narrow allowlist
    rather than RaceSerializer minus a few fields: no notes, priority, training block, target
    power or anything else from the athlete's training, so a field later added to RaceSerializer
    never becomes public by accident."""

    athlete_name = serializers.CharField(source="athlete.name", read_only=True)
    goal_time = serializers.DurationField(read_only=True)
    result_time = serializers.DurationField(read_only=True)
    activity = serializers.SerializerMethodField()
    course = serializers.SerializerMethodField()

    class Meta:
        model = Race
        fields = [
            "id",
            "athlete_name",
            "name",
            "date",
            "sport",
            "distance_km",
            "goal_time",
            "result_time",
            "url",
            "results_url",
            "activity",
            "course",
        ]

    def get_activity(self, race: Race) -> dict | None:
        # Only what the result card shows alongside the time - the linked activity itself stays
        # private unless it's separately shared.
        activity = race.activity
        if activity is None:
            return None
        return {
            "avg_hr": activity.avg_hr,
            "avg_power": activity.avg_power,
            "avg_air_temp": activity.avg_air_temp,
            "avg_humidity": activity.avg_humidity,
        }

    def get_course(self, race: Race) -> dict | None:
        if race.course is None:
            return None
        ensure_elevation_series(race.course)
        data = CourseSerializer(race.course).data
        return {field: data[field] for field in PUBLIC_COURSE_FIELDS}


class RaceCommentSerializer(serializers.ModelSerializer):
    author_id = serializers.CharField(read_only=True)
    author_name = serializers.CharField(source="author.name", read_only=True)

    class Meta:
        model = RaceComment
        fields = ["id", "race_id", "author_id", "author_name", "role", "text", "week", "parent_id", "created_at"]
        read_only_fields = ["id", "race_id", "author_id", "author_name", "role", "created_at"]


class RaceCommentCreateSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=2000, trim_whitespace=True)
    week = serializers.IntegerField(required=False, allow_null=True, min_value=1)
    parent_id = serializers.CharField(required=False, allow_null=True)

    def validate_text(self, value: str) -> str:
        if not value.strip():
            raise serializers.ValidationError("Comment text cannot be empty.")
        return value
