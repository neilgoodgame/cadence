import base64
import time
import uuid
from collections.abc import Sequence
from pathlib import Path
from typing import Any, cast

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
from django.conf import settings


def _load_private_key() -> bytes:
    return Path(settings.JWT_PRIVATE_KEY_PATH).read_bytes()


def _load_public_key() -> bytes:
    return Path(settings.JWT_PUBLIC_KEY_PATH).read_bytes()


def mint_jwt(sub: str, athlete_id: str, scopes: Sequence[str], expires_in: int) -> tuple[str, dict[str, Any]]:
    now = int(time.time())
    claims = {
        "iss": settings.JWT_ISSUER,
        "sub": sub,
        "athlete_id": athlete_id,
        "aud": settings.JWT_AUDIENCE,
        "scope": " ".join(scopes),
        "iat": now,
        "exp": now + expires_in,
        "jti": f"jwt_{uuid.uuid4().hex[:12]}",
    }
    token = jwt.encode(
        claims,
        _load_private_key(),
        algorithm="RS256",
        headers={"kid": settings.JWT_KID},
    )
    return token, claims


def decode_jwt(token: str) -> dict[str, Any]:
    return cast(
        dict[str, Any],
        jwt.decode(
            token,
            _load_public_key(),
            algorithms=["RS256"],
            audience=settings.JWT_AUDIENCE,
            issuer=settings.JWT_ISSUER,
        ),
    )


def _b64url_uint(value: int) -> str:
    length = (value.bit_length() + 7) // 8
    raw = value.to_bytes(length, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def get_jwks() -> dict[str, Any]:
    public_key = serialization.load_pem_public_key(_load_public_key())
    if not isinstance(public_key, RSAPublicKey):
        raise TypeError("JWT_PUBLIC_KEY_PATH must contain an RSA public key (this deployment is RS256-only)")
    numbers = public_key.public_numbers()
    return {
        "keys": [
            {
                "kty": "RSA",
                "use": "sig",
                "alg": "RS256",
                "kid": settings.JWT_KID,
                "n": _b64url_uint(numbers.n),
                "e": _b64url_uint(numbers.e),
            }
        ]
    }
