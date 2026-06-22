from rest_framework import serializers

from .models import Bike, Component, ServiceRecord, Shoe


class ComponentSerializer(serializers.ModelSerializer):
    class Meta:
        model = Component
        fields = ["id", "bike_id", "name", "km", "limit_km", "model"]
        read_only_fields = ["id", "bike_id"]


class ComponentCreateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Component
        fields = ["name", "limit_km", "km", "model"]
        extra_kwargs = {
            "km": {"required": False},
            "model": {"required": False},
        }


class ComponentUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Component
        fields = ["name", "limit_km", "km", "model"]
        extra_kwargs = {field: {"required": False} for field in fields}


class BikeSerializer(serializers.ModelSerializer):
    components = serializers.IntegerField(source="components.count", read_only=True)

    class Meta:
        model = Bike
        fields = ["id", "athlete_id", "name", "kind", "groupset", "distance_km", "hours", "rides", "components"]
        read_only_fields = ["id", "athlete_id", "hours", "rides", "components"]


class BikeDetailSerializer(BikeSerializer):
    components = ComponentSerializer(many=True, read_only=True)

    class Meta(BikeSerializer.Meta):
        fields = BikeSerializer.Meta.fields


class BikeCreateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Bike
        fields = ["name", "kind", "groupset", "distance_km"]
        extra_kwargs = {
            "kind": {"required": False},
            "groupset": {"required": False},
            "distance_km": {"required": False},
        }


class BikeUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Bike
        fields = ["name", "kind", "groupset", "distance_km"]
        extra_kwargs = {field: {"required": False} for field in fields}


class ServiceRecordSerializer(serializers.ModelSerializer):
    class Meta:
        model = ServiceRecord
        fields = ["id", "component_id", "action", "reset", "note", "date"]
        read_only_fields = ["id", "component_id"]


class ServiceRecordCreateSerializer(serializers.ModelSerializer):
    class Meta:
        model = ServiceRecord
        fields = ["action", "reset", "note", "date"]
        extra_kwargs = {
            "action": {"required": False},
            "reset": {"required": False},
            "note": {"required": False},
            "date": {"required": False},
        }


class ShoeSerializer(serializers.ModelSerializer):
    manufacturer = serializers.SerializerMethodField()
    model = serializers.SerializerMethodField()
    version = serializers.CharField(source="shoe_model_version.version", read_only=True)

    class Meta:
        model = Shoe
        fields = [
            "id",
            "athlete_id",
            "shoe_model_version_id",
            "manufacturer",
            "model",
            "version",
            "colourway",
            "name",
            "image",
            "role",
            "km",
            "limit_km",
            "since",
        ]
        read_only_fields = ["id", "athlete_id", "since"]

    def get_manufacturer(self, obj: Shoe) -> str:
        return obj.shoe_model_version.shoe_model.manufacturer

    def get_model(self, obj: Shoe) -> str:
        return obj.shoe_model_version.shoe_model.model


class ShoeCreateSerializer(serializers.Serializer):
    shoe_model_version_id = serializers.CharField()
    colourway = serializers.CharField()
    name = serializers.CharField(required=False, allow_blank=False)
    limit_km = serializers.IntegerField(required=False)
    image = serializers.URLField(required=False, allow_null=True)


class ShoeUpdateSerializer(serializers.ModelSerializer):
    class Meta:
        model = Shoe
        fields = ["name", "limit_km", "km", "image", "retired"]
        extra_kwargs = {field: {"required": False} for field in fields}


class ShoeCatalogEntrySerializer(serializers.Serializer):
    shoe_model_version_id = serializers.CharField()
    manufacturer = serializers.CharField()
    model = serializers.CharField()
    version = serializers.CharField()
    display_name = serializers.CharField()
