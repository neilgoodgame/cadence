from django.contrib import admin

from .models import Webhook, WebhookDelivery

admin.site.register(Webhook)
admin.site.register(WebhookDelivery)
