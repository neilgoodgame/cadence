from rest_framework import serializers

from .models import Webhook


class WebhookSerializer(serializers.ModelSerializer):
    class Meta:
        model = Webhook
        fields = ["id", "url", "status", "events"]


class WebhookCreateSerializer(serializers.Serializer):
    url = serializers.URLField()
    events = serializers.ListField(child=serializers.CharField())

    def validate_events(self, value: list[str]) -> list[str]:
        if not value:
            raise serializers.ValidationError("At least one event is required.")
        unknown = [e for e in value if e not in Webhook.EVENT_CHOICES]
        if unknown:
            raise serializers.ValidationError(f"Unknown event(s): {', '.join(unknown)}.")
        return value
