#!/bin/sh
set -e

KEYS_DIR=/app/keys

# Flyway runs automatically on Spring context startup, so unlike the Python
# backend's entrypoint there is no separate migrate step (and no second
# container polling a ".ready" sentinel - Spring Batch jobs here run
# in-process, there's no celery-worker equivalent to wait on).
if [ ! -f "$KEYS_DIR/jwt_private.pem" ]; then
    openssl genrsa -out "$KEYS_DIR/jwt_private_pkcs1.pem" 2048
    openssl pkcs8 -topk8 -nocrypt -in "$KEYS_DIR/jwt_private_pkcs1.pem" -out "$KEYS_DIR/jwt_private.pem"
    openssl rsa -in "$KEYS_DIR/jwt_private_pkcs1.pem" -pubout -out "$KEYS_DIR/jwt_public.pem"
    rm "$KEYS_DIR/jwt_private_pkcs1.pem"
fi

exec "$@"
