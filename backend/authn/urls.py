from django.urls import path

from .views import CreateJwtView, JwksView

urlpatterns = [
    path("v1/auth/jwt", CreateJwtView.as_view(), name="create-jwt"),
    path(".well-known/jwks.json", JwksView.as_view(), name="jwks"),
]
