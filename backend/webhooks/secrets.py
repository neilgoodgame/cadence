import secrets
import string

_ALPHABET = string.ascii_letters + string.digits
PREFIX = "whsec_"


def generate_secret(length: int = 32) -> str:
    random_part = "".join(secrets.choice(_ALPHABET) for _ in range(length))
    return f"{PREFIX}{random_part}"
