import secrets
import string

_ALPHABET = string.ascii_letters + string.digits


def _random(length=40):
    return "".join(secrets.choice(_ALPHABET) for _ in range(length))


def generate_access_token(request=None):
    return f"cad_at_{_random()}"


def generate_refresh_token(request=None):
    return f"cad_rt_{_random()}"
