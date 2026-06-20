import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR.parent / ".env")


def env(key: str, default: str | None = None) -> str | None:
    return os.environ.get(key, default)


def env_bool(key: str, default: bool = False) -> bool:
    val = os.environ.get(key)
    if val is None:
        return default
    return val.lower() in ("1", "true", "yes", "on")


def env_list(key: str, default: str = "") -> list[str]:
    val = os.environ.get(key, default)
    return [item.strip() for item in val.split(",") if item.strip()]


SECRET_KEY = env("DJANGO_SECRET_KEY", "insecure-dev-key-change-me")
DEBUG = env_bool("DJANGO_DEBUG", True)
ALLOWED_HOSTS = env_list("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1")

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    # third party
    "rest_framework",
    "oauth2_provider",
    "corsheaders",
    "drf_spectacular",
    # local apps
    "accounts",
    "authn",
    "athletes",
    "activities",
    "uploads",
    "workouts",
    "scheduling",
    "gear",
    "webhooks",
    "core",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "corsheaders.middleware.CorsMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": env("POSTGRES_DB", "cadence"),
        "USER": env("POSTGRES_USER", "cadence"),
        "PASSWORD": env("POSTGRES_PASSWORD", "cadence"),
        "HOST": env("POSTGRES_HOST", "localhost"),
        "PORT": env("POSTGRES_PORT", "5432"),
    }
}

AUTH_USER_MODEL = "accounts.User"

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = BASE_DIR / "staticfiles"

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# --- CORS (frontend phase will tighten this) ---
CORS_ALLOWED_ORIGINS = env_list("CORS_ALLOWED_ORIGINS", "http://localhost:5173,http://localhost:3000")

# --- REST framework ---
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "oauth2_provider.contrib.rest_framework.OAuth2Authentication",
        "core.authentication.JWTAuthentication",
        "core.authentication.PersonalAccessTokenAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"],
    "DEFAULT_PAGINATION_CLASS": "core.pagination.CadenceCursorPagination",
    "PAGE_SIZE": 50,
    "EXCEPTION_HANDLER": "core.exceptions.cadence_exception_handler",
    "DEFAULT_SCHEMA_CLASS": "drf_spectacular.openapi.AutoSchema",
    "TEST_REQUEST_DEFAULT_FORMAT": "json",
}

SPECTACULAR_SETTINGS = {
    "TITLE": "Cadence API",
    "DESCRIPTION": "REST API for the Cadence training platform.",
    "VERSION": "1.0.0",
}

# --- OAuth2 (django-oauth-toolkit) ---
OAUTH2_PROVIDER = {
    "ACCESS_TOKEN_EXPIRE_SECONDS": 21600,
    "REFRESH_TOKEN_EXPIRE_SECONDS": 60 * 60 * 24 * 30,
    "ROTATE_REFRESH_TOKEN": True,
    "ACCESS_TOKEN_GENERATOR": "authn.token_generators.generate_access_token",
    "REFRESH_TOKEN_GENERATOR": "authn.token_generators.generate_refresh_token",
    # openapi.yaml's documented /oauth/token request body has no code_challenge/
    # code_verifier fields; the toolkit defaults PKCE_REQUIRED to True, which would
    # reject every request that follows the documented contract literally.
    "PKCE_REQUIRED": False,
    "SCOPES": {
        "activities:read": "Read activities and streams",
        "activities:write": "Upload and edit activities",
        "workouts:write": "Create and edit workouts",
        "calendar:write": "Schedule workouts",
        "coach": "Access an athlete roster",
        "gear:write": "Manage gear",
    },
}

# --- JWT signing (scoped delegated JWTs minted via /v1/auth/jwt) ---
JWT_PRIVATE_KEY_PATH = env("JWT_PRIVATE_KEY_PATH", str(BASE_DIR / "keys" / "jwt_private.pem"))
JWT_PUBLIC_KEY_PATH = env("JWT_PUBLIC_KEY_PATH", str(BASE_DIR / "keys" / "jwt_public.pem"))
JWT_KID = env("JWT_KID", "801")
JWT_ISSUER = env("JWT_ISSUER", "https://api.cadence.cc")
JWT_AUDIENCE = env("JWT_AUDIENCE", "cadence-api")

# --- Celery ---
REDIS_URL = env("REDIS_URL", "redis://localhost:6379/0")
CELERY_BROKER_URL = env("CELERY_BROKER_URL", REDIS_URL)
CELERY_RESULT_BACKEND = env("CELERY_RESULT_BACKEND", REDIS_URL)
CELERY_ACCEPT_CONTENT = ["json"]
CELERY_TASK_SERIALIZER = "json"
CELERY_RESULT_SERIALIZER = "json"
CELERY_TASK_ALWAYS_EAGER = env_bool("CELERY_TASK_ALWAYS_EAGER", False)

# --- Uploads ---
MAX_UPLOAD_SIZE_BYTES = 200 * 1024 * 1024
MAX_BATCH_FILES = 500
FILE_UPLOAD_MAX_MEMORY_SIZE = 10 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = MAX_UPLOAD_SIZE_BYTES
MEDIA_URL = "media/"
MEDIA_ROOT = BASE_DIR / "media"
