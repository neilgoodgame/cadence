from django.urls import path

from .views import WebhookDetailView, WebhookListCreateView

urlpatterns = [
    path("v1/webhooks", WebhookListCreateView.as_view(), name="webhook-list"),
    path("v1/webhooks/<str:id>", WebhookDetailView.as_view(), name="webhook-detail"),
]
