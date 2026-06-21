from typing import Any

from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework import status
from rest_framework.exceptions import ValidationError
from rest_framework.permissions import AllowAny
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from authn.oauth_utils import issue_token_pair
from core.auth_context import authenticated_user, get_request_scopes
from core.derived import compute_compliance, compute_fitness_series, compute_flags, compute_tsb
from core.exceptions import ConflictError, InvalidCredentialsError

from .models import PersonalAccessToken, User, UserRelationship
from .serializers import (
    AccessTokenSerializer,
    CoachAthleteSerializer,
    CoachedAthleteSerializer,
    CreateAccessTokenSerializer,
    CreateShareSerializer,
    LoginSerializer,
    RegisterSerializer,
    RosterEntrySerializer,
    ShareSerializer,
    UpdateShareSerializer,
    UserSerializer,
)
from .tokens import generate_secret, hash_secret, visible_prefix


def _athlete_with_tokens(user: User) -> dict[str, Any]:
    access_token, refresh_token = issue_token_pair(user)
    return {
        "athlete": UserSerializer(user).data,
        "tokens": {
            "access_token": access_token.token,
            "refresh_token": refresh_token.token,
            "token_type": "Bearer",
            "expires_in": int((access_token.expires - timezone.now()).total_seconds()),
            "scope": access_token.scope,
        },
    }


def _share_payload(relationship: UserRelationship) -> dict[str, Any]:
    return {
        "id": relationship.id,
        "name": relationship.grantee.name,
        "handle": relationship.grantee.handle,
        "role": relationship.role,
        "status": relationship.status,
        "since": relationship.created.date(),
    }


class RegisterView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request: Request) -> Response:
        serializer = RegisterSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        if data.get("provider"):
            raise ValidationError(
                {"error": {"type": "invalid_request_error", "message": "Social signup is not yet available."}}
            )

        if User.objects.filter(email__iexact=data["email"]).exists():
            raise ConflictError("An account with that email already exists.")

        user = User.objects.create_user(email=data["email"], password=data["password"], name=data["name"])
        return Response(_athlete_with_tokens(user), status=status.HTTP_201_CREATED)


class LoginView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request: Request) -> Response:
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        user = User.objects.filter(email__iexact=data["email"]).first()
        if user is None or not user.check_password(data["password"]):
            raise InvalidCredentialsError()

        return Response(_athlete_with_tokens(user), status=status.HTTP_200_OK)


class MeView(APIView):
    def get(self, request: Request) -> Response:
        return Response(UserSerializer(request.user).data)


class ContextsView(APIView):
    def get(self, request: Request) -> Response:
        user = authenticated_user(request)

        coaching_qs = UserRelationship.objects.filter(
            grantee=user, status=UserRelationship.STATUS_ACTIVE
        ).select_related("owner")
        coaching = [
            {
                "relationship_id": rel.id,
                "user_id": rel.owner_id,
                "name": rel.owner.name,
                "role": rel.role,
                "compliance": compute_compliance(rel.owner_id),
                "tsb": compute_tsb(rel.owner_id),
                "last_activity_at": None,
            }
            for rel in coaching_qs
        ]

        coached_by_qs = UserRelationship.objects.filter(owner=user).select_related("grantee")
        coached_by = [
            {
                "id": rel.id,
                "name": rel.grantee.name,
                "handle": rel.grantee.handle,
                "role": rel.role,
                "status": rel.status,
                "since": rel.created.date(),
            }
            for rel in coached_by_qs
        ]

        return Response(
            {
                "self": UserSerializer(user).data,
                "coaching": CoachedAthleteSerializer(coaching, many=True).data,
                "coached_by": ShareSerializer(coached_by, many=True).data,
            }
        )


class AccessTokenListCreateView(APIView):
    def get(self, request: Request) -> Response:
        tokens = PersonalAccessToken.objects.filter(user=authenticated_user(request)).order_by("-created")
        return Response({"data": AccessTokenSerializer(tokens, many=True).data})

    def post(self, request: Request) -> Response:
        serializer = CreateAccessTokenSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        caller_scopes = get_request_scopes(request)
        requested_scopes = set(data["scopes"])
        if caller_scopes and not requested_scopes.issubset(caller_scopes):
            raise ValidationError(
                {
                    "error": {
                        "type": "invalid_request_error",
                        "param": "scopes",
                        "message": "Scopes must be a subset of your own token's scopes.",
                    }
                }
            )

        secret = generate_secret()
        pat = PersonalAccessToken.objects.create(
            user=authenticated_user(request),
            name=data["name"],
            prefix=visible_prefix(secret),
            hashed_secret=hash_secret(secret),
            scopes=data["scopes"],
            expires_at=data.get("expires_at"),
        )
        payload = AccessTokenSerializer(pat).data
        payload["secret"] = secret
        return Response(payload, status=status.HTTP_201_CREATED)


class AccessTokenDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        pat = get_object_or_404(PersonalAccessToken, pk=id, user=authenticated_user(request))
        pat.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class AccessTokenRotateView(APIView):
    def post(self, request: Request, id: str) -> Response:
        pat = get_object_or_404(PersonalAccessToken, pk=id, user=authenticated_user(request))
        secret = generate_secret()
        pat.prefix = visible_prefix(secret)
        pat.hashed_secret = hash_secret(secret)
        pat.save(update_fields=["prefix", "hashed_secret"])
        payload = AccessTokenSerializer(pat).data
        payload["secret"] = secret
        return Response(payload)


class ShareListCreateView(APIView):
    def get(self, request: Request) -> Response:
        shares = (
            UserRelationship.objects.filter(owner=authenticated_user(request))
            .select_related("grantee")
            .order_by("-created")
        )
        data = [_share_payload(rel) for rel in shares]
        return Response({"data": ShareSerializer(data, many=True).data})

    def post(self, request: Request) -> Response:
        serializer = CreateShareSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        invitee = serializer.validated_data["invitee"]
        role = serializer.validated_data["role"]
        owner = authenticated_user(request)

        if invitee.startswith("@"):
            grantee = User.objects.filter(handle__iexact=invitee).first()
        else:
            grantee = User.objects.filter(email__iexact=invitee).first()
        if grantee is None:
            raise ValidationError(
                {
                    "error": {
                        "type": "invalid_request_error",
                        "param": "invitee",
                        "message": "No user found with that email or handle.",
                    }
                }
            )
        if grantee.id == owner.id:
            raise ValidationError(
                {
                    "error": {
                        "type": "invalid_request_error",
                        "param": "invitee",
                        "message": "You cannot invite yourself.",
                    }
                }
            )

        relationship, created = UserRelationship.objects.get_or_create(
            owner=owner,
            grantee=grantee,
            defaults={"role": role, "status": UserRelationship.STATUS_PENDING},
        )
        if not created:
            raise ConflictError("That person already has access to your data.")

        return Response(ShareSerializer(_share_payload(relationship)).data, status=status.HTTP_201_CREATED)


class ShareDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        relationship = get_object_or_404(UserRelationship, pk=id, owner=authenticated_user(request))
        serializer = UpdateShareSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        relationship.role = serializer.validated_data["role"]
        relationship.save(update_fields=["role"])
        return Response(ShareSerializer(_share_payload(relationship)).data)

    def delete(self, request: Request, id: str) -> Response:
        relationship = get_object_or_404(UserRelationship, pk=id, owner=authenticated_user(request))
        relationship.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class RosterListView(APIView):
    def get(self, request: Request) -> Response:
        relationships = UserRelationship.objects.filter(
            grantee=authenticated_user(request), status=UserRelationship.STATUS_ACTIVE
        ).select_related("owner")
        data = [
            {
                "athlete_id": rel.owner_id,
                "name": rel.owner.name,
                "compliance": compute_compliance(rel.owner_id),
                "tsb": compute_tsb(rel.owner_id),
                "flags": compute_flags(rel.owner_id),
            }
            for rel in relationships
        ]
        return Response({"data": RosterEntrySerializer(data, many=True).data})


class CoachAthleteDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        get_object_or_404(
            UserRelationship,
            owner_id=id,
            grantee=authenticated_user(request),
            status=UserRelationship.STATUS_ACTIVE,
        )
        today = timezone.now().date()
        series = compute_fitness_series(id, today, today)
        point = series[0] if series else {"ctl": 0, "atl": 0, "tsb": 0}
        data = {
            "athlete_id": id,
            "ctl": point["ctl"],
            "atl": point["atl"],
            "tsb": point["tsb"],
            "next_workout": None,
        }
        return Response(CoachAthleteSerializer(data).data)
