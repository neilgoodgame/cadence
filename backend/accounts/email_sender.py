"""Transactional email out of the API - mirrors backend_java's email/ package
(EmailService/SesEmailService/LoggingEmailService). Two backends selected by
settings.EMAIL_PROVIDER: "ses" (real send, prod/staging) or "log" (this module's own default -
logs the link at INFO instead of sending, since a local box has no AWS credentials or verified
SES identity to actually deliver anything through).
"""

import logging
from typing import Any

import boto3
from botocore.exceptions import ClientError
from celery import shared_task
from django.conf import settings

from .models import User

logger = logging.getLogger(__name__)


def send_verification_email(user: User, verification_link: str) -> None:
    """Queues the actual send via Celery - the caller (email_verification.issue_and_send) has
    already committed the token row by the time this returns, so a slow or failed SES call never
    delays or fails the HTTP response it was triggered from (same reasoning as backend_java's
    @Async SesEmailService)."""
    _send_verification_email_task.delay(user.id, verification_link)


@shared_task(bind=True, max_retries=0)  # type: ignore[untyped-decorator]
def _send_verification_email_task(self: Any, user_id: str, verification_link: str) -> None:
    user = User.objects.filter(pk=user_id).first()
    if user is None:
        return
    if settings.EMAIL_PROVIDER == "ses":
        _send_via_ses(user, verification_link)
    else:
        logger.info("Verification email for %s <%s>: %s", user.name, user.email, verification_link)


def _send_via_ses(user: User, verification_link: str) -> None:
    text_body = (
        f"Hi {user.name},\n\n"
        "Confirm your email address to finish setting up your Cadence account:\n\n"
        f"{verification_link}"
        "\n\nThis link expires in 24 hours. If you didn't create a Cadence account, "
        "you can safely ignore this email."
    )
    client = boto3.client("sesv2", region_name=settings.SES_REGION)
    try:
        client.send_email(
            FromEmailAddress=settings.EMAIL_FROM_ADDRESS,
            Destination={"ToAddresses": [user.email]},
            Content={
                "Simple": {
                    "Subject": {"Data": "Verify your Cadence email address"},
                    "Body": {"Text": {"Data": text_body}},
                }
            },
        )
    except ClientError:
        # No request left to fail here (see email_verification.py's issue_and_send docstring) -
        # the athlete can always hit resend-verification.
        logger.exception("Failed to send verification email to user %s", user.id)
