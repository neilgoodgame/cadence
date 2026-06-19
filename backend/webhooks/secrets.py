import secrets
import string

_ALPHABET = string.ascii_letters + string.digits
PREFIX = "whsec_"


def generate_secret(length=32):
    random_part = "".join(secrets.choice(_ALPHABET) for _ in range(length))
    return f"{PREFIX}{random_part}"
