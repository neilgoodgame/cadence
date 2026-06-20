from django.shortcuts import get_object_or_404
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Webhook
from .secrets import generate_secret
from .serializers import WebhookCreateSerializer, WebhookSerializer


class WebhookListCreateView(APIView):
    def get(self, request):
        webhooks = Webhook.objects.filter(owner=request.user).order_by("-created")
        return Response({"data": WebhookSerializer(webhooks, many=True).data})

    def post(self, request):
        serializer = WebhookCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        secret = generate_secret()
        webhook = Webhook.objects.create(owner=request.user, url=data["url"], events=data["events"], secret=secret)
        payload = WebhookSerializer(webhook).data
        payload["secret"] = secret
        return Response(payload, status=201)


class WebhookDetailView(APIView):
    def delete(self, request, id):
        webhook = get_object_or_404(Webhook, pk=id, owner=request.user)
        webhook.delete()
        return Response(status=204)
