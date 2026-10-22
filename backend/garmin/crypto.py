"""AES-256-GCM for Garmin Personal API keys at rest.

The key comes from ``settings.GARMIN_ENCRYPTION_KEY`` (base64, 32 bytes). It is deliberately not
defaulted, so an environment that forgot to set it fails closed (503 on save) rather than quietly
using a well-known key. The owning athlete's id is bound in as AAD so a ciphertext copied onto
another athlete's row fails to decrypt. Mirrors ``GarminKeyCipher`` in backend_java.
"""

import base64
import binascii
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from django.conf import settings
from rest_framework.exceptions import APIException

VERSION = "v1."
IV_BYTES = 12


class GarminNotConfiguredError(APIException):
    status_code = 503
    default_detail = "Garmin sync is not configured on this server."
    default_code = "service_unavailable"


def _aesgcm() -> AESGCM:
    configured = (getattr(settings, "GARMIN_ENCRYPTION_KEY", "") or "").strip()
    if not configured:
        raise GarminNotConfiguredError()
    try:
        raw = base64.b64decode(configured, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise RuntimeError("GARMIN_ENCRYPTION_KEY must be base64.") from exc
    if len(raw) != 32:
        raise RuntimeError("GARMIN_ENCRYPTION_KEY must decode to exactly 32 bytes.")
    return AESGCM(raw)


def encrypt(plaintext: str, aad: str) -> str:
    iv = os.urandom(IV_BYTES)
    ct = _aesgcm().encrypt(iv, plaintext.encode(), aad.encode())
    return VERSION + base64.urlsafe_b64encode(iv + ct).rstrip(b"=").decode()


def decrypt(stored: str, aad: str) -> str:
    if not stored.startswith(VERSION):
        raise RuntimeError("Unrecognised Garmin key ciphertext version.")
    body = stored[len(VERSION) :]
    try:
        data = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
        return _aesgcm().decrypt(data[:IV_BYTES], data[IV_BYTES:], aad.encode()).decode()
    except (InvalidTag, binascii.Error, ValueError) as exc:
        raise RuntimeError("Could not decrypt the Garmin key.") from exc
