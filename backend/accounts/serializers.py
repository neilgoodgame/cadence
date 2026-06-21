from datetime import date
from typing import Any

from rest_framework import serializers

from .models import PersonalAccessToken, User, UserRelationship


class UserSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = [
            "id",
            "name",
            "email",
            "age",
            "weight_kg",
            "ftp",
            "critical_run_power",
            "threshold_pace",
            "lthr",
            "max_hr",
            "is_coach",
        ]


class RegisterSerializer(serializers.Serializer):
    name = serializers.CharField()
    email = serializers.EmailField(required=False)
    password = serializers.CharField(required=False, min_length=10, write_only=True)
    provider = serializers.ChoiceField(choices=["strava", "google", "apple"], required=False)
    id_token = serializers.CharField(required=False)

    def validate(self, attrs: dict[str, Any]) -> dict[str, Any]:
        if attrs.get("provider"):
            if not attrs.get("id_token"):
                raise serializers.ValidationError("id_token is required for social signup.")
        elif not attrs.get("email") or not attrs.get("password"):
            raise serializers.ValidationError("email and password are required for email signup.")
        return attrs


class LoginSerializer(serializers.Serializer):
    email = serializers.EmailField()
    password = serializers.CharField(write_only=True)


class CoachedAthleteSerializer(serializers.Serializer):
    relationship_id = serializers.CharField()
    user_id = serializers.CharField()
    name = serializers.CharField()
    role = serializers.CharField()
    compliance = serializers.FloatField()
    tsb = serializers.IntegerField()
    last_activity_at = serializers.DateTimeField(allow_null=True)


class ShareSerializer(serializers.Serializer):
    id = serializers.CharField()
    name = serializers.CharField()
    handle = serializers.CharField(allow_null=True)
    role = serializers.CharField()
    status = serializers.CharField()
    since = serializers.DateField()


class CreateShareSerializer(serializers.Serializer):
    invitee = serializers.CharField()
    role = serializers.ChoiceField(choices=[UserRelationship.ROLE_VIEWER, UserRelationship.ROLE_COACH])


class UpdateShareSerializer(serializers.Serializer):
    role = serializers.ChoiceField(choices=[UserRelationship.ROLE_VIEWER, UserRelationship.ROLE_COACH])


class RosterEntrySerializer(serializers.Serializer):
    athlete_id = serializers.CharField()
    name = serializers.CharField()
    compliance = serializers.FloatField()
    tsb = serializers.IntegerField()
    flags = serializers.IntegerField()


class CoachAthleteSerializer(serializers.Serializer):
    athlete_id = serializers.CharField()
    ctl = serializers.IntegerField()
    atl = serializers.IntegerField()
    tsb = serializers.IntegerField()
    next_workout = serializers.CharField(allow_null=True)


class AccessTokenSerializer(serializers.ModelSerializer):
    created = serializers.SerializerMethodField()

    class Meta:
        model = PersonalAccessToken
        fields = ["id", "name", "prefix", "scopes", "created", "expires_at", "last_used"]

    def get_created(self, obj: PersonalAccessToken) -> date:
        return obj.created.date()


class CreateAccessTokenSerializer(serializers.Serializer):
    name = serializers.CharField()
    scopes = serializers.ListField(child=serializers.CharField(), allow_empty=False)
    expires_at = serializers.DateField(required=False, allow_null=True)
