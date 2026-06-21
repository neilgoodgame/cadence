from django.shortcuts import get_object_or_404
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import authenticated_user

from .models import Webhook
from .secrets import generate_secret
from .serializers import WebhookCreateSerializer, WebhookSerializer


class WebhookListCreateView(APIView):
    def get(self, request: Request) -> Response:
        webhooks = Webhook.objects.filter(owner=authenticated_user(request)).order_by("-created")
        return Response({"data": WebhookSerializer(webhooks, many=True).data})

    def post(self, request: Request) -> Response:
        serializer = WebhookCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        secret = generate_secret()
        webhook = Webhook.objects.create(
            owner=authenticated_user(request), url=data["url"], events=data["events"], secret=secret
        )
        payload = WebhookSerializer(webhook).data
        payload["secret"] = secret
        return Response(payload, status=201)


class WebhookDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        webhook = get_object_or_404(Webhook, pk=id, owner=authenticated_user(request))
        webhook.delete()
        return Response(status=204)
