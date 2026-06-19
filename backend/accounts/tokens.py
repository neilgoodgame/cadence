import hashlib
import secrets
import string

_ALPHABET = string.ascii_letters + string.digits
PREFIX = "cad_pat_"
PREFIX_VISIBLE_CHARS = 4


def generate_secret(length=28):
    random_part = "".join(secrets.choice(_ALPHABET) for _ in range(length))
    return f"{PREFIX}{random_part}"


def visible_prefix(secret):
    return secret[: len(PREFIX) + PREFIX_VISIBLE_CHARS]


def hash_secret(secret):
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()
