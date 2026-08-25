import os
from pathlib import Path
from typing import overload

from celery.schedules import crontab
from dotenv import load_dotenv

# Imported eagerly (not referenced as a dotted-path string) because django-oauth-toolkit's
# PKCE_REQUIRED setting isn't in its IMPORT_STRINGS list (confirmed live against the installed
# version's settings.py) - unlike ACCESS_TOKEN_GENERATOR/REFRESH_TOKEN_GENERATOR below, which are
# resolved lazily from strings, oauth2_validators.py does a plain `callable(oauth2_settings.PKCE_REQUIRED)`
# check, so the settings dict needs the real function object. Safe to import here: it's a
# dependency-free module (constants + one pure function), not a Django app needing the app
# registry to be ready yet.
from authn.mcp_oauth import pkce_required

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR.parent / ".env")


@overload
def env(key: str, default: str) -> str: ...
@overload
def env(key: str, default: None = None) -> str | None: ...
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
    "mcp_server",
    # local apps
    "accounts",
    "authn",
    "athletes",
    "activities",
    "uploads",
    "workouts",
    "scheduling",
    "gear",
    "adminapi",
    "races",
    "dataexport",
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

# Only ever hit via oauth2_provider.views.AuthorizationView's LoginRequiredMixin redirect - the
# frontend's own login flow is the JSON /v1/auth/login API (accounts/views.py) and never uses
# Django sessions or this URL. See authn/login_views.py.
LOGIN_URL = "authn-login"

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
    # code_verifier fields, so PKCE can't be required globally (the toolkit defaults to True) or
    # the first-party client's documented contract would be rejected. Required per-client instead
    # (a bool-returning callable is a supported PKCE_REQUIRED value) so the real third-party MCP
    # client - which always sends PKCE - can still be held to it. See authn/mcp_oauth.py.
    "PKCE_REQUIRED": pkce_required,
    "SCOPES": {
        "activities:read": "Read activities and streams",
        "activities:write": "Upload and edit activities",
        "workouts:write": "Create and edit workouts",
        "calendar:write": "Schedule workouts",
        "coach": "Access an athlete roster",
        "gear:write": "Manage gear",
        # Not Cadence-meaningful on its own; advertising it is what makes Claude's OAuth client
        # auto-request a refresh token when connecting. Global (not cadence-mcp-only) because
        # django-oauth-toolkit has no per-Application scope allowlist without an add-on - see
        # authn/mcp_oauth.py.
        "offline_access": "Retain offline access (refresh tokens)",
    },
}

# --- MCP OAuth client (cadence-mcp) ---
# Client secret for the "cadence-mcp" Application (seeded by
# authn/migrations/0001_seed_mcp_oauth_application.py). Distinct from the first-party client's
# secret, which the toolkit auto-generates - this one must be a stable, known value on every
# environment because the migration seeds a fixed client_id/secret pair rather than generating
# one at boot. Matches Java's OAUTH_MCP_CLIENT_SECRET naming for cross-backend consistency.
OAUTH_MCP_CLIENT_SECRET = env("OAUTH_MCP_CLIENT_SECRET", "insecure-dev-mcp-secret-change-me")

# This API's own issuer identity, used in RFC 8414/9728 discovery document responses (see
# authn/discovery_views.py). Matches Java's OAUTH_ISSUER / cadence.oauth.issuer naming.
OAUTH_ISSUER = env("OAUTH_ISSUER", "http://localhost:8000")

# --- MCP server (django-mcp-server) ---
# Reuses the same bearer-token paths already in REST_FRAMEWORK's DEFAULT_AUTHENTICATION_CLASSES
# above - not a parallel auth stack. PersonalAccessTokenAuthentication matches Java's /mcp,
# which accepts a cad_pat_... token via the same BearerSchemeAuthenticationManagerResolver every
# other endpoint uses (confirmed live) - needed for a virtual coach's delegated personal access
# token (see accounts.delegation) to authenticate an MCP client without a full OAuth2 flow.
DJANGO_MCP_AUTHENTICATION_CLASSES = [
    "authn.mcp_auth.McpOAuth2Authentication",
    "core.authentication.PersonalAccessTokenAuthentication",
]

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

# Runs embedded in celery-worker (docker-compose.yml's `-B` flag), not a separate
# beat service - this is the only periodic task in the app, doesn't warrant one.
CELERY_BEAT_SCHEDULE = {
    "ensure-record-partitions": {
        "task": "activities.tasks.ensure_record_partitions",
        "schedule": crontab(day_of_month=1, hour=0, minute=0),
    },
}

# --- Uploads ---
MAX_UPLOAD_SIZE_BYTES = 200 * 1024 * 1024
MAX_BATCH_FILES = 50000
FILE_UPLOAD_MAX_MEMORY_SIZE = 10 * 1024 * 1024
MEDIA_URL = "media/"
MEDIA_ROOT = BASE_DIR / "media"

# --- Data export/import ---
# A full-account data export/import file can be much larger than a single activity upload
# (the real test account's export was already 210MB) - MAX_IMPORT_SIZE_BYTES is enforced
# explicitly in dataexport/views.py, but DATA_UPLOAD_MAX_MEMORY_SIZE (the Django-wide request
# body ceiling) has to be raised to at least that value too, or the request gets rejected
# before dataexport's own check ever runs. MAX_UPLOAD_SIZE_BYTES (activity uploads) is
# unaffected - it's still enforced on its own in uploads/services.py.
MAX_IMPORT_SIZE_BYTES = 2 * 1024 * 1024 * 1024

# --- Email verification ---
# Mirrors backend_java's cadence.email.* properties exactly (same env var names) - see
# accounts/email_sender.py. EMAIL_PROVIDER "log" (this default) logs the verification link at
# INFO instead of sending it - a local box has neither AWS credentials nor a verified SES
# identity to actually deliver anything through; "ses" is what staging/prod set.
EMAIL_PROVIDER = env("EMAIL_PROVIDER", "log")
EMAIL_FROM_ADDRESS = env("EMAIL_FROM_ADDRESS", "no-reply@cadence.cc")
# Where the frontend's "verify your email" screen lives - the token is appended as ?token=...
EMAIL_VERIFICATION_BASE_URL = env("EMAIL_VERIFICATION_BASE_URL", "http://localhost:5173/verify-email")
EMAIL_VERIFICATION_TTL_HOURS = int(env("EMAIL_VERIFICATION_TTL_HOURS", "24"))
EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS = int(env("EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS", "60"))
SES_REGION = env("SES_REGION", "eu-west-2")
DATA_UPLOAD_MAX_MEMORY_SIZE = MAX_IMPORT_SIZE_BYTES
