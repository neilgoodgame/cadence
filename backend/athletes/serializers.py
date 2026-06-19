from rest_framework import serializers

from accounts.models import User

from .zones import reference_for


class AthleteUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ["name", "age", "ftp", "critical_run_power", "threshold_pace", "lthr", "max_hr"]
        extra_kwargs = {field: {"required": False} for field in fields}


class ZoneSerializer(serializers.Serializer):
    name = serializers.CharField()
    low_pct = serializers.FloatField()
    high_pct = serializers.FloatField()


class ZoneSetSerializer(serializers.Serializer):
    type = serializers.CharField()
    reference = serializers.SerializerMethodField()
    zones = ZoneSerializer(many=True)

    def get_reference(self, obj):
        return reference_for(obj.athlete, obj.type)


class ZoneSetReplaceSerializer(serializers.Serializer):
    zones = ZoneSerializer(many=True)


class FitnessPointSerializer(serializers.Serializer):
    date = serializers.DateField()
    ctl = serializers.FloatField()
    atl = serializers.FloatField()
    tsb = serializers.FloatField()
