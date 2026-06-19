import base64
import time
import uuid
from pathlib import Path

import jwt
from cryptography.hazmat.primitives import serialization
from django.conf import settings


def _load_private_key():
    return Path(settings.JWT_PRIVATE_KEY_PATH).read_bytes()


def _load_public_key():
    return Path(settings.JWT_PUBLIC_KEY_PATH).read_bytes()


def mint_jwt(sub, athlete_id, scopes, expires_in):
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


def decode_jwt(token):
    return jwt.decode(
        token,
        _load_public_key(),
        algorithms=["RS256"],
        audience=settings.JWT_AUDIENCE,
        issuer=settings.JWT_ISSUER,
    )


def _b64url_uint(value):
    length = (value.bit_length() + 7) // 8
    raw = value.to_bytes(length, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def get_jwks():
    public_key = serialization.load_pem_public_key(_load_public_key())
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
