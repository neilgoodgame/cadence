#!/bin/bash
set -eu

dnf install -y docker jq >/dev/null
systemctl enable --now docker

if ! command -v aws >/dev/null 2>&1; then
	curl -sS "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o /tmp/awscliv2.zip
	unzip -q /tmp/awscliv2.zip -d /tmp
	/tmp/aws/install
	rm -rf /tmp/awscliv2.zip /tmp/aws
fi

mkdir -p /opt/cadence/media /opt/cadence/keys
chmod 700 /opt/cadence/keys

# Fetches secrets fresh and (re)starts the container - run on every boot AND
# every crash-restart (systemd's Restart=always below), never just once at
# first launch. Same principle as entrypoint.sh's JWT key materialization on
# ECS: never trust a locally cached copy, since the RDS password in
# particular can be rotated in the background at any time.
#
# Secrets never appear as `docker run -e KEY=value` arguments - that puts
# them in the process's command line, which `ps`/`systemctl status`/
# `journalctl` all echo straight back (found the hard way: a routine status
# check during this instance's first boot leaked all four secrets). Instead:
# single-line secrets go in a 600-permission --env-file (read directly by
# the Docker daemon, never becomes a command-line argument); the multi-line
# JWT PEM values are written straight to files and bind-mounted at
# /app/keys - entrypoint.sh already skips its own generation step whenever
# those files already exist, so this needs zero application-code changes.
cat > /opt/cadence/run.sh <<'RUNSCRIPT'
#!/bin/bash
set -eu

REGION="${region}"
docker rm -f cadence-backend >/dev/null 2>&1 || true

# Read fresh on every start, same principle as the secrets below - a deploy
# just updates this parameter and restarts the service, no Terraform/reboot
# involved. See infra/CICD_DEPLOY_SKETCH.md for why this isn't a Terraform
# variable baked into user_data instead.
IMAGE_TAG=$(aws ssm get-parameter --name "${image_tag_parameter_name}" --region "$REGION" --query 'Parameter.Value' --output text)

DB_PASSWORD=$(aws secretsmanager get-secret-value --secret-id "${db_secret_arn}" --region "$REGION" --query SecretString --output text | jq -r '.password')
JWT_JSON=$(aws secretsmanager get-secret-value --secret-id "${jwt_secret_arn}" --region "$REGION" --query SecretString --output text)
OAUTH_SECRET=$(aws secretsmanager get-secret-value --secret-id "${oauth_secret_arn}" --region "$REGION" --query SecretString --output text)
OAUTH_MCP_SECRET=$(aws secretsmanager get-secret-value --secret-id "${oauth_mcp_secret_arn}" --region "$REGION" --query SecretString --output text)

echo "$JWT_JSON" | jq -r '.jwt_private_key_pem' > /opt/cadence/keys/jwt_private.pem
echo "$JWT_JSON" | jq -r '.jwt_public_key_pem' > /opt/cadence/keys/jwt_public.pem
chmod 600 /opt/cadence/keys/jwt_private.pem /opt/cadence/keys/jwt_public.pem

# Fixed path, overwritten fresh on every run rather than a fresh mktemp name
# each time - avoids either leaking a new filename to clean up or (worse)
# leaving stale copies scattered around if a crash skips a cleanup step.
# 600 permissions, root-only, persists for the container's lifetime - same
# accepted-risk shape Docker's own /root/.docker/config.json already has on
# this single-tenant, SSM-only instance. The alternative (delete right after
# docker run reads it) needs `-d` + a separate wait mechanism, and
# `docker wait` always exits 0 regardless of the container's real exit code -
# that would silently break systemd's Restart=always crash detection below.
ENV_FILE=/opt/cadence/env
(
	umask 077
	printf 'POSTGRES_PASSWORD=%s\n' "$DB_PASSWORD"
	printf 'OAUTH_FIRST_PARTY_CLIENT_SECRET=%s\n' "$OAUTH_SECRET"
	printf 'OAUTH_MCP_CLIENT_SECRET=%s\n' "$OAUTH_MCP_SECRET"
) > "$ENV_FILE"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${ecr_registry}"

exec docker run --rm --name cadence-backend \
	-p 8080:8080 \
	-v /opt/cadence/media:/app/media \
	-v /opt/cadence/keys:/app/keys \
	--env-file "$ENV_FILE" \
	-e POSTGRES_HOST="${db_address}" \
	-e POSTGRES_PORT=5432 \
	-e POSTGRES_DB="${db_name}" \
	-e POSTGRES_USER="${db_username}" \
	-e JWT_KID="${jwt_kid}" \
	-e JWT_ISSUER="${jwt_issuer}" \
	-e JWT_AUDIENCE="${jwt_audience}" \
	-e OAUTH_ISSUER="${oauth_issuer}" \
	-e CORS_ALLOWED_ORIGINS="${cors_allowed_origins}" \
	-e EMAIL_FROM_ADDRESS="${email_from_address}" \
	-e EMAIL_VERIFICATION_BASE_URL="${email_verification_base_url}" \
	-e SES_REGION="${ses_region}" \
	--log-driver awslogs \
	--log-opt awslogs-region="$REGION" \
	--log-opt awslogs-group="${log_group_name}" \
	--log-opt awslogs-stream=backend \
	"${ecr_registry}/${ecr_repo_name}:$IMAGE_TAG"
RUNSCRIPT
chmod +x /opt/cadence/run.sh

cat > /etc/systemd/system/cadence-backend.service <<'UNIT'
[Unit]
Description=Cadence Java backend
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
ExecStart=/opt/cadence/run.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now cadence-backend
