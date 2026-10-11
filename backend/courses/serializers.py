from rest_framework import serializers

from .models import Course


class CourseRaceSerializer(serializers.Serializer):
    id = serializers.CharField()
    name = serializers.CharField()
    date = serializers.DateField()
    priority = serializers.CharField()
    result_s = serializers.SerializerMethodField()

    def get_result_s(self, race) -> int | None:
        return int(race.result_time.total_seconds()) if race.result_time else None


class CourseSerializer(serializers.ModelSerializer):
    race_count = serializers.SerializerMethodField()
    races = serializers.SerializerMethodField()

    class Meta:
        model = Course
        fields = [
            "id",
            "athlete_id",
            "name",
            "location_name",
            "start_lat",
            "start_lng",
            "distance_km",
            "elevation_gain_m",
            "elevation_loss_m",
            "elevation_max_m",
            "elevation_min_m",
            "is_loop",
            "source",
            "source_activity_id",
            "gpx_filename",
            "route_polyline",
            "elevation_profile",
            "climbs",
            "created_at",
            "race_count",
            "races",
        ]
        read_only_fields = [f for f in fields if f not in ("name", "location_name")]

    def get_race_count(self, course: Course) -> int:
        return course.races.count()

    def get_races(self, course: Course) -> list[dict]:
        return CourseRaceSerializer(course.races.order_by("-date"), many=True).data


class CourseListSerializer(serializers.ModelSerializer):
    race_count = serializers.SerializerMethodField()

    class Meta:
        model = Course
        fields = ["id", "athlete_id", "name", "location_name", "distance_km", "source", "created_at", "race_count"]
        read_only_fields = fields

    def get_race_count(self, course: Course) -> int:
        return course.races.count()


class CourseUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Course
        fields = ["name", "location_name"]
        extra_kwargs = {"name": {"required": False}, "location_name": {"required": False}}
