"""Issues and redeems the email-verification token sent to (password-signup) athletes -
mirrors backend_java's EmailVerificationService. Tokens are stored hashed (SHA-256, looked up
directly by hash - no visible prefix needed since, unlike a personal access token, this is never
typed by a human or reused across requests).
"""

import hashlib
import secrets
import string
from datetime import timedelta

from django.conf import settings
from django.utils import timezone
from rest_framework.exceptions import Throttled, ValidationError

from core.exceptions import ConflictError

from .email_sender import send_verification_email
from .models import EmailVerificationToken, User

_TOKEN_PREFIX = "cad_evt_"
_ALPHABET = string.ascii_letters + string.digits
_SECRET_LENGTH = 40


def _generate_secret() -> str:
    random_part = "".join(secrets.choice(_ALPHABET) for _ in range(_SECRET_LENGTH))
    return f"{_TOKEN_PREFIX}{random_part}"


def _hash(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def issue_and_send(user: User) -> None:
    """Generates and persists a fresh token, then hands the raw value off to be mailed. Doesn't
    check whether the athlete is already verified or under a resend cooldown - resend() runs
    those first; registration doesn't need to since the user was just created unverified."""
    raw_secret = _generate_secret()
    EmailVerificationToken.objects.create(
        user=user,
        hashed_secret=_hash(raw_secret),
        expires_at=timezone.now() + timedelta(hours=settings.EMAIL_VERIFICATION_TTL_HOURS),
    )
    link = f"{settings.EMAIL_VERIFICATION_BASE_URL}?token={raw_secret}"
    send_verification_email(user, link)


def verify(raw_secret: str) -> None:
    token = EmailVerificationToken.objects.filter(hashed_secret=_hash(raw_secret)).first()
    if token is None or not token.is_usable(timezone.now()):
        raise ValidationError({"token": "This verification link is invalid or has expired."})

    token.used_at = timezone.now()
    token.save(update_fields=["used_at"])

    user = token.user
    user.email_verified = True
    user.save(update_fields=["email_verified"])


def resend(user: User) -> None:
    if user.email_verified:
        raise ConflictError("This email address is already verified.")
    last = EmailVerificationToken.objects.filter(user=user).order_by("-created").first()
    if last is not None:
        cooldown_ends = last.created + timedelta(seconds=settings.EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS)
        if timezone.now() < cooldown_ends:
            raise Throttled(detail="A verification email was just sent - wait a bit before requesting another.")
    issue_and_send(user)
