from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id, get_request_scopes

from .jwt_utils import get_jwks, mint_jwt

WRITE_SCOPES = {"activities:write", "workouts:write", "calendar:write", "gear:write"}
DEFAULT_JWT_SCOPES = ["activities:read"]
MAX_EXPIRES_IN = 86400
DEFAULT_EXPIRES_IN = 3600


class CreateJwtView(APIView):
    def post(self, request):
        sub, _ = get_effective_athlete_id(request)
        athlete_id = request.data.get("athlete_id") or sub
        scopes = request.data.get("scopes") or DEFAULT_JWT_SCOPES
        expires_in = request.data.get("expires_in", DEFAULT_EXPIRES_IN)

        if not isinstance(scopes, list) or not all(isinstance(s, str) for s in scopes):
            raise ValidationError(
                {"error": {"type": "invalid_request_error", "param": "scopes", "message": "scopes must be an array of strings."}}
            )
        if not isinstance(expires_in, int) or isinstance(expires_in, bool) or expires_in <= 0 or expires_in > MAX_EXPIRES_IN:
            raise ValidationError(
                {
                    "error": {
                        "type": "invalid_request_error",
                        "param": "expires_in",
                        "message": f"expires_in must be a positive integer up to {MAX_EXPIRES_IN}.",
                    }
                }
            )

        caller_scopes = get_request_scopes(request)
        if caller_scopes and not set(scopes).issubset(caller_scopes):
            raise ValidationError(
                {"error": {"type": "invalid_request_error", "param": "scopes", "message": "scopes must be a subset of the caller's own scopes."}}
            )

        if athlete_id != sub:
            from accounts.models import UserRelationship

            relationship = UserRelationship.objects.filter(
                owner_id=athlete_id, grantee_id=sub, status=UserRelationship.STATUS_ACTIVE
            ).first()
            if relationship is None:
                raise PermissionDenied("You do not have access to that athlete's data.")
            if relationship.role == UserRelationship.ROLE_VIEWER and any(s in WRITE_SCOPES for s in scopes):
                raise PermissionDenied("Viewer access is read-only.")

        token, claims = mint_jwt(sub=sub, athlete_id=athlete_id, scopes=scopes, expires_in=expires_in)
        return Response(
            {"token": token, "token_type": "Bearer", "expires_in": expires_in, "claims": claims},
            status=status.HTTP_201_CREATED,
        )


class JwksView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def get(self, request):
        return Response(get_jwks())
