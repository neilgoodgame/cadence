"""Coach-delegation helpers shared by delegated personal access token creation
(AccessTokenListCreateView) and virtual coach account creation (VirtualCoachCreateView).
Mirrors backend_java's DelegationPolicy + SharingService.createVirtualCoach.
"""

import secrets
import string
import uuid
from typing import Any

from django.db import transaction
from rest_framework.exceptions import PermissionDenied

from .models import PersonalAccessToken, User, UserRelationship
from .tokens import generate_secret, hash_secret, visible_prefix

WRITE_SCOPES = {"activities:write", "workouts:write", "calendar:write", "gear:write"}

_PASSWORD_ALPHABET = string.ascii_letters + string.digits
_PASSWORD_LENGTH = 24


def _generate_password() -> str:
    return "".join(secrets.choice(_PASSWORD_ALPHABET) for _ in range(_PASSWORD_LENGTH))


def require_active_coach_access(athlete_id: str, coach_id: str, requested_scopes: list[str]) -> None:
    relationship = UserRelationship.objects.filter(
        owner_id=athlete_id, grantee_id=coach_id, status=UserRelationship.STATUS_ACTIVE
    ).first()
    if relationship is None:
        raise PermissionDenied("You do not have access to that athlete's data.")
    if relationship.role == UserRelationship.ROLE_VIEWER and any(s in WRITE_SCOPES for s in requested_scopes):
        raise PermissionDenied("Viewer access is read-only.")


@transaction.atomic
def create_virtual_coach(athlete: User, name: str, scopes: list[str]) -> dict[str, Any]:
    """Creates a synthetic "virtual coach" account (no real inbox - see User.is_virtual's
    comment, but a real, usable password so it can complete an interactive OAuth login the same
    way any other account does) belonging to nobody but this one relationship, an already-ACTIVE
    coach relationship granting it access to `athlete` (no invite/accept needed - the athlete
    created it themselves), and a personal access token delegated to `athlete` for MCP clients
    that accept a bearer token directly. Password and token secret are both revealed once and
    never retrievable again.
    """
    password = _generate_password()
    coach = User.objects.create_user(
        email=f"virtual+{uuid.uuid4()}@social.cadence.invalid",
        password=password,
        name=name,
        email_verified=True,
        is_coach=True,
        is_virtual=True,
    )

    relationship = UserRelationship.objects.create(
        owner=athlete, grantee=coach, role=UserRelationship.ROLE_COACH, status=UserRelationship.STATUS_ACTIVE
    )

    secret = generate_secret()
    token = PersonalAccessToken.objects.create(
        user=coach,
        name="MCP access",
        prefix=visible_prefix(secret),
        hashed_secret=hash_secret(secret),
        scopes=scopes,
        delegated_athlete=athlete,
    )

    return {
        "relationship": relationship,
        "token": token,
        "secret": secret,
        "email": coach.email,
        "password": password,
    }
