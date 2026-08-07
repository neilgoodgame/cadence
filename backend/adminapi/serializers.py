from rest_framework import serializers


class ShoeVersionUsageSerializer(serializers.Serializer):
    version = serializers.CharField()
    usage_count = serializers.IntegerField()


class AdminShoeCatalogEntrySerializer(serializers.Serializer):
    id = serializers.CharField()
    manufacturer = serializers.CharField()
    model = serializers.CharField()
    versions = ShoeVersionUsageSerializer(many=True)
    added_by = serializers.CharField(allow_null=True)


class AdminShoeCatalogCreateSerializer(serializers.Serializer):
    manufacturer = serializers.CharField(max_length=150)
    model = serializers.CharField(max_length=150)
    version = serializers.CharField(max_length=50)


class AdminShoeVersionCreateSerializer(serializers.Serializer):
    version = serializers.CharField(max_length=50)


class AdminUserSerializer(serializers.Serializer):
    id = serializers.CharField()
    name = serializers.CharField()
    email = serializers.CharField()
    date_joined = serializers.DateTimeField()
    is_coach = serializers.BooleanField()
    is_admin = serializers.BooleanField()


class AdminUserUpdateSerializer(serializers.Serializer):
    is_coach = serializers.BooleanField(required=False)
    is_admin = serializers.BooleanField(required=False)


class AdminRelationshipSerializer(serializers.Serializer):
    id = serializers.CharField()
    coach_name = serializers.CharField()
    athlete_name = serializers.CharField()
    role = serializers.CharField()
    granted = serializers.DateTimeField()


class CatalogAuditLogEntrySerializer(serializers.Serializer):
    id = serializers.CharField()
    description = serializers.CharField()
    action = serializers.CharField()
    by = serializers.CharField(allow_null=True)
    created = serializers.DateTimeField()
