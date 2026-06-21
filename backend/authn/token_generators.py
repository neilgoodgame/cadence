import secrets
import string
from typing import Any

_ALPHABET = string.ascii_letters + string.digits


def _random(length: int = 40) -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(length))


def generate_access_token(request: Any = None) -> str:
    return f"cad_at_{_random()}"


def generate_refresh_token(request: Any = None) -> str:
    return f"cad_rt_{_random()}"
