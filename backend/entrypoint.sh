#!/bin/sh
set -e

KEYS_DIR=/app/keys
READY_FILE="$KEYS_DIR/.ready"

if [ "$1" = "celery" ]; then
    # The backend container owns key generation + migration; wait for it so
    # celery-worker never starts against an unmigrated schema or missing keys.
    until [ -f "$READY_FILE" ]; do
        sleep 1
    done
else
    # Matches both "gunicorn" (prod) and "python manage.py runserver" (dev)
    # commands for the backend service.
    mkdir -p "$KEYS_DIR"
    if [ ! -f "$KEYS_DIR/jwt_private.pem" ]; then
        openssl genrsa -out "$KEYS_DIR/jwt_private.pem" 2048
        openssl rsa -in "$KEYS_DIR/jwt_private.pem" -pubout -out "$KEYS_DIR/jwt_public.pem"
    fi
    python manage.py migrate --noinput
    touch "$READY_FILE"
fi

exec "$@"
