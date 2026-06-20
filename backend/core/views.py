from django.http import HttpRequest, JsonResponse


def healthcheck(request: HttpRequest) -> JsonResponse:
    return JsonResponse({"status": "ok"})
