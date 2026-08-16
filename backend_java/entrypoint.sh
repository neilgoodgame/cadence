#!/bin/sh
set -e

KEYS_DIR=/app/keys

# Flyway runs automatically on Spring context startup, so unlike the Python
# backend's entrypoint there is no separate migrate step (and no second
# container polling a ".ready" sentinel - Spring Batch jobs here run
# in-process, there's no celery-worker equivalent to wait on).
if [ -n "$JWT_PRIVATE_KEY_PEM" ] && [ -n "$JWT_PUBLIC_KEY_PEM" ]; then
    # ECS/Fargate: the key comes from Secrets Manager via these injected env vars
    # (ECS task secrets are env-var-only, there's no native file-mount for them).
    # Written unconditionally on every boot, not gated on "file doesn't exist" -
    # Fargate's filesystem is ephemeral, so that check would always be true anyway,
    # and every task/replica must materialize the exact same key every time or JWTs
    # issued by one task would fail validation on another.
    printf '%s' "$JWT_PRIVATE_KEY_PEM" > "$KEYS_DIR/jwt_private.pem"
    printf '%s' "$JWT_PUBLIC_KEY_PEM" > "$KEYS_DIR/jwt_public.pem"
elif [ ! -f "$KEYS_DIR/jwt_private.pem" ]; then
    # Local dev only: generate once, persisted across container restarts on the
    # jwt_keys named volume.
    openssl genrsa -out "$KEYS_DIR/jwt_private_pkcs1.pem" 2048
    openssl pkcs8 -topk8 -nocrypt -in "$KEYS_DIR/jwt_private_pkcs1.pem" -out "$KEYS_DIR/jwt_private.pem"
    openssl rsa -in "$KEYS_DIR/jwt_private_pkcs1.pem" -pubout -out "$KEYS_DIR/jwt_public.pem"
    rm "$KEYS_DIR/jwt_private_pkcs1.pem"
fi

exec "$@"
