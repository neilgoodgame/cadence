import os

from django.http import HttpRequest, JsonResponse


def healthcheck(request: HttpRequest) -> JsonResponse:
    # Mirrors backend_java's HealthController - "unknown" here in practice, since this backend
    # isn't deployed anywhere a BUILD_SHA build arg would get baked in, but kept at parity in
    # case that changes.
    return JsonResponse({"status": "ok", "build": os.environ.get("BUILD_SHA", "unknown")})
