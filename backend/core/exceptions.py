from typing import Any

from rest_framework.exceptions import APIException
from rest_framework.response import Response
from rest_framework.views import exception_handler


class ConflictError(APIException):
    status_code = 409
    default_detail = "The request conflicts with existing state."
    default_code = "conflict"


class CQLParseError(Exception):
    """Raised by core.cql when a `q` filter string is malformed."""

    def __init__(self, message: str, param: str = "q"):
        self.message = message
        self.param = param
        super().__init__(message)


def _error_envelope(error_type: str, code: str, message: str, param: str | None = None) -> dict[str, Any]:
    return {
        "error": {
            "type": error_type,
            "code": code,
            "message": message,
            "param": param,
        }
    }


def cadence_exception_handler(exc: Exception, context: dict[str, Any]) -> Response | None:
    if isinstance(exc, CQLParseError):
        return Response(
            _error_envelope("invalid_request_error", "invalid_query", exc.message, exc.param),
            status=400,
        )

    response = exception_handler(exc, context)
    if response is None:
        return None

    code_by_status = {
        400: "bad_request",
        401: "unauthorized",
        403: "forbidden",
        404: "not_found",
        405: "method_not_allowed",
        409: "conflict",
        413: "payload_too_large",
        429: "rate_limited",
    }
    type_by_status = {
        400: "invalid_request_error",
        401: "authentication_error",
        403: "authorization_error",
        404: "not_found_error",
        405: "invalid_request_error",
        409: "conflict_error",
        413: "invalid_request_error",
        429: "rate_limit_error",
    }
    code = code_by_status.get(response.status_code, "error")
    error_type = type_by_status.get(response.status_code, "api_error")

    detail = response.data
    if isinstance(detail, dict) and "detail" in detail:
        message = str(detail["detail"])
    else:
        message = str(detail)

    response.data = _error_envelope(error_type, code, message)
    return response
