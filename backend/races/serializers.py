from rest_framework import serializers

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
        ]
        extra_kwargs = {field: {"required": False} for field in fields}


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
