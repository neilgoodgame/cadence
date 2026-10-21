# Single-VM deployment: shared design and migration steps

**Status: design sketch, nothing built.** Read this first; the per-provider
docs (`HETZNER.md`, `OVH.md`, `SCALEWAY.md`, `VULTR.md`) only cover what
differs by provider, and `COMPARISON.md` ranks them. Same spirit as
`../EC2_BACKEND_SKETCH.md`: a design record, not a runbook that has been
exercised.

Goal: run the whole of Cadence (frontend, Java API, Postgres) on **one
virtual machine** from a non-AWS provider costing **$20/month or less**,
with backups managed by us rather than by the provider.

## What moves, and what replaces it

| Today (AWS staging) | On the VM |
|---|---|
| CloudFront + S3 (frontend) | Caddy serving `frontend/dist` |
| CloudFront + EC2 `t4g.small` (API) | Caddy reverse proxy to the Java container |
| RDS `db.t4g.micro`, Postgres 16 | `postgres:16` container, bind-mounted data dir |
| ECR + SSM parameter + SSM Run Command deploys | `docker save \| ssh \| docker load`, then `systemctl restart` |
| Secrets Manager | `600`-permission env file and key files on the VM |
| CloudWatch Logs (`awslogs` driver) | Docker `json-file` driver with rotation |
| ACM certificates | Caddy automatic Let's Encrypt |
| Route 53 | Unchanged (just repoint A records) or the provider's DNS |
| SES | **Unchanged.** See "Email" below |
| Terraform (`infra/terraform`) | Manual / provider CLI for now (see "Not covered") |

## Sizing

The app container was sized by hard experience (see the OOM history in
`terraform/modules/ec2/user_data.sh.tpl` and the Dockerfile's JVM flags).
Do not go below 4 GB RAM.

| Component | Memory budget |
|---|---|
| Java container (`mem_limit`, `memswap_limit` equal, so no swap) | 1.8 GB |
| Postgres container | 0.7 GB |
| Caddy | ~0.05 GB |
| OS, Docker, sshd | ~0.4 GB |
| **Total** | **~3.0 GB of 4 GB** |

Staging runs the container at 1400m on a 2 GB box; 1800m here is deliberate
headroom because the JVM's off-heap use exceeded its heap in the
2026-10-16 OOM (see the NMT comment in the Dockerfile). Keep a 2 GB swap
file on the host for the OS only, as `user_data.sh.tpl` does.

Disk: 40 GB is plenty (Postgres data, media, images, dumps). Check
`du -sh /opt/cadence/media` on staging before choosing a plan.

## Layout on the VM

```
/opt/cadence/
  docker-compose.yml
  Caddyfile
  env              # 600, secrets (POSTGRES_PASSWORD, OAUTH_* secrets, AWS SES keys)
  keys/            # 700, jwt_private.pem + jwt_public.pem (600)
  media/           # bind mount -> /app/media
  pgdata/          # bind mount -> postgres data dir
  www/             # built frontend (dist/ contents)
  backups/         # local staging area for dumps before upload
```

### docker-compose.yml

```yaml
services:
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./www:/srv/www:ro
      - caddy_data:/data
    depends_on: [backend]

  db:
    image: postgres:16
    restart: unless-stopped
    env_file: env                      # supplies POSTGRES_PASSWORD
    environment:
      POSTGRES_DB: cadence
      POSTGRES_USER: cadence
    command: >-
      postgres -c shared_buffers=256MB -c effective_cache_size=1GB
      -c max_connections=50
    volumes: ["./pgdata:/var/lib/postgresql/data"]
    mem_limit: 700m
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cadence"]
      interval: 5s
      retries: 10
    # No "ports:" - Postgres is reachable only on the compose network.

  backend:
    image: cadence-backend-java:${IMAGE_TAG}
    restart: unless-stopped
    env_file: env
    depends_on:
      db: { condition: service_healthy }
    environment:
      POSTGRES_HOST: db
      POSTGRES_PORT: 5432
      POSTGRES_DB: cadence
      POSTGRES_USER: cadence
      JWT_KID: <same as staging>
      JWT_ISSUER: <same as staging>
      JWT_AUDIENCE: <same as staging>
      OAUTH_ISSUER: https://api.cadence.bioinform.co.uk
      CORS_ALLOWED_ORIGINS: https://cadence.bioinform.co.uk
      EMAIL_PROVIDER: ses
      SES_REGION: eu-west-2
      EMAIL_FROM_ADDRESS: <same as staging>
      EMAIL_VERIFICATION_BASE_URL: <same as staging>
    volumes:
      - ./keys:/app/keys
      - ./media:/app/media
    mem_limit: 1800m
    memswap_limit: 1800m
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }

volumes:
  caddy_data:
```

Copy every non-secret value from the staging `user_data.sh.tpl` template
variables (`terraform output` / the rendered `/opt/cadence/run.sh`) so the
two environments match. `entrypoint.sh` skips key generation when the PEM
files already exist, exactly as on EC2.

### Caddyfile

```
cadence.bioinform.co.uk {
	root * /srv/www
	encode zstd gzip
	@assets path /assets/*
	header @assets Cache-Control "public, max-age=31536000, immutable"
	@shell not path /assets/*
	header @shell Cache-Control "no-cache"
	try_files {path} /index.html
	file_server
}

api.cadence.bioinform.co.uk {
	reverse_proxy backend:8080 {
		# The recompute endpoints stream SSE; without this Caddy buffers
		# the stream and the UI sees nothing until it finishes.
		flush_interval -1
	}
}
```

Do not add `encode` to the API block: compression buffers SSE.

## Email

`EMAIL_PROVIDER` supports only `ses` and `log` (see `application.yml`), so
SES stays. From outside AWS, SES needs credentials: create a dedicated IAM
user with only `ses:SendEmail` (and `ses:SendRawEmail` if used) on the
verified identity, and put `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in
the VM's `env` file. The SDK's default credential chain picks them up. Some
providers block outbound port 25; SES over HTTPS (443) is unaffected.
Replacing SES with another sender needs a code change and is out of scope.

## Host hardening (all providers)

- Key-only SSH, `PermitRootLogin no`, a non-root deploy user in the `docker`
  group. Restrict port 22 to your own IP in the provider firewall if you
  have a stable address.
- Provider firewall: allow 80 and 443 from anywhere, 22 as above, nothing else.
- `unattended-upgrades` for security patches; reboot windows are a manual
  call (see "Operational differences" in `COMPARISON.md`).
- `env` and `keys/` are `600`/`700`. Never pass secrets as `docker run -e`
  or compose literals; that is the 2026-10 secret-exposure incident from the
  README's Step 7. Compose `env_file` is read by the daemon and is safe.
- 2 GB swap file, `vm.swappiness=10`.

## Deploys

Replacement for `infra/scripts/deploy-backend.sh` (not written yet):

```sh
# build for the VM's architecture: linux/amd64 for OVH, Scaleway, Vultr and
# Hetzner CX; linux/arm64 only for Hetzner CAX
docker buildx build --platform linux/amd64 --provenance=false --sbom=false \
    -t cadence-backend-java:$TAG --load backend_java
docker save cadence-backend-java:$TAG | gzip | ssh deploy@VM \
    'gunzip | docker load'
ssh deploy@VM "cd /opt/cadence && IMAGE_TAG=$TAG docker compose up -d backend \
    && docker image prune -af --filter 'until=24h'"
# then poll https://api.cadence.bioinform.co.uk/healthz as the AWS script does
```

No registry is needed, so no registry credentials live on the VM. Per the
`feedback_warn_before_backend_deploy` rule, a restart still kills any
in-flight SSE recompute, so warn before deploying.

The frontend replacement for `deploy-frontend.sh` builds with the same
`VITE_API_BASE_URL` and `VITE_OAUTH_CLIENT_SECRET` (read from wherever you
keep it now; a missing secret breaks silent token refresh with
`invalid_client`) and runs `rsync -a --delete frontend/dist/ deploy@VM:/opt/cadence/www/`.
No cache invalidation step exists or is needed: hashed assets are immutable
and `index.html` is `no-cache`.

## Backups (we own these)

The provider's snapshot is a convenience, not the plan. Three things must be
backed up: the **database**, the **media directory**, and the **JWT keys**
(plus the `env` file, via your password manager rather than the backup).

Nightly via a systemd timer or cron:

```sh
#!/bin/sh
set -eu
D=/opt/cadence/backups; TS=$(date +%F-%H%M); mkdir -p "$D"
docker exec cadence-db-1 pg_dump -U cadence -Fc cadence > "$D/db-$TS.dump"
tar czf "$D/media-$TS.tgz" -C /opt/cadence media keys
for f in "$D"/db-$TS.dump "$D"/media-$TS.tgz; do
    age -r "$AGE_PUBLIC_KEY" -o "$f.age" "$f" && rm "$f"
done
rclone copy "$D" offsite:cadence-backups --include "*-$TS.*.age"
find "$D" -name '*.age' -mtime +7 -delete        # local retention
curl -fsS -m 10 "$HEALTHCHECK_URL" >/dev/null    # dead-man's switch
```

- Encrypt before upload (`age`); dumps hold emails, tokens and training data.
- Offsite target: any S3-compatible bucket (your existing AWS account is
  pennies), or Backblaze B2, or a pull from a home machine. Not the same
  provider's disk only.
- Retention: 7 dailies locally; on the remote use bucket lifecycle rules
  for 7 daily, 4 weekly, 3 monthly.
- Keep the private `age` key off the VM.
- Take a provider snapshot before Flyway migrations, OS upgrades and resizes.
- **Restore test**: restore the latest dump into a scratch Postgres container
  and check row counts, once now and then quarterly.
- Staging today has no backup for `media/` beyond the EBS volume, so this is
  an improvement, not a regression.

## Migration from AWS staging

Approximate downtime: 10 to 30 minutes, dominated by the dump/restore.

1. **Prepare the VM** (provider doc): server, firewall, SSH, Docker, swap,
   `/opt/cadence` layout, compose and Caddyfile in place. Do not start Caddy
   on the real hostnames yet.
2. **Copy the JWT keys** from the staging secrets (the `jwt_private_key_pem`
   and `jwt_public_key_pem` JSON fields) into `/opt/cadence/keys/` so existing
   access and refresh tokens stay valid. Copy the OAuth client secrets and
   `POSTGRES_PASSWORD` for the new DB into `env`. Use your normal
   secret-handling workflow, never paste them into chat or logs.
3. **Dry run first**: with a throwaway hostname (or `/etc/hosts` override),
   take a dump with `infra/scripts/sync-staging-db.sh dump -c`, restore it
   into the VM's `db` container (`pg_restore --no-owner -d cadence`), start
   `backend` and confirm Flyway sees the schema as current and `/healthz`
   returns OK. Repeat the real dump during cutover for fresh data.
4. **Copy media**: size it first (`du -sh /opt/cadence/media` on staging).
   Staging's instance has no SSH, so use an SSM session to stage it
   somewhere reachable (for example a scratch S3 prefix with a presigned
   URL) and `rsync`/`aws s3 cp` it into `/opt/cadence/media`. Confirm file
   ownership matches the container user.
5. **Build and ship** the image and frontend with the scripts above.
6. **Lower DNS TTL** to 60s a day ahead.
7. **Cutover**: warn that the backend is going down (no in-flight SSE
   recomputes); stop the EC2 container (or put staging in read-only by
   stopping the service); take the final dump and media sync; restore; start
   the stack; Caddy issues certificates on first request to each hostname.
   Point `cadence.` and `api.` A records at the VM (and AAAA if the VM has
   IPv6; otherwise remove AAAA records so IPv6 clients do not hit CloudFront).
8. **Verify**: log in, reload (silent refresh works), open an activity,
   trigger a recompute and watch it stream, request an email verification.
9. **Keep AWS for a week** in a stopped state (stop EC2 and RDS via
   `infra/scripts/staging-env.sh`) as the rollback. Roll back by repointing
   DNS. Then `terraform destroy` the unused modules, retaining SES and the
   Route 53 zone if still in use. Remember the CloudFront ACM validation
   records and the SES DKIM records live in Route 53 and must survive.

## Variant: keep the frontend on AWS (hybrid)

Instead of serving the SPA from the VM, leave it on S3 + CloudFront and move
only the API and Postgres. This is the lower-risk, incremental option, and it
costs about $1 to 2 a month more (S3, CloudFront, Route 53 at light traffic,
per `../EC2_BACKEND_SKETCH.md`).

What stays unchanged:
- `infra/scripts/deploy-frontend.sh` (S3 sync + CloudFront invalidation).
- The `frontend`, `acm` and Route 53 Terraform modules.
- `VITE_API_BASE_URL` and `CORS_ALLOWED_ORIGINS`; the app already uses
  separate `cadence.` and `api.` hostnames.

What changes versus the all-on-VM design above:
- Drop the `cadence.bioinform.co.uk` block from the Caddyfile, the `./www`
  mount and the `www/` directory, and the frontend rsync step.
- Caddy serves only the API hostname. Keep its automatic Let's Encrypt
  certificate: CloudFront needs a valid certificate on the origin.
- Choose how `api.` reaches the VM:
  1. **Keep CloudFront in front of the API** (today's shape). Repoint the
     `api_cdn` module's origin from the EC2 host to the VM, using the
     existing `backend_origin` record in Route 53 (an A record pointing at
     the VM, on a hostname distinct from the public `api.` one). The VM's IP
     stays out of public DNS and SSE streaming works as it does now
     (`CachingDisabled`). Restrict the VM firewall's 80/443 to CloudFront's
     managed prefix list if the provider supports it (Hetzner, Vultr and
     Scaleway firewalls take IP lists; the list changes, so script the
     update or skip the restriction).
  2. **Skip CloudFront for the API.** Point the `api.` A record straight at
     the VM. Simpler and cheaper, but the VM's IP is public and you lose the
     CDN's connection buffering.
- Migration step 7 changes: DNS cutover only touches `api.` (or the
  CloudFront origin), and `cadence.` is left alone, so the frontend has no
  downtime and no rollback step of its own.
- Rollback is easier: the frontend never moves, so only the API origin has to
  be pointed back at EC2.

Trade-offs: AWS stays in the stack (Terraform state, a second bill, an
account to keep secure); the SPA gets a global CDN; the frontend remains up
during a VM outage, showing API errors rather than nothing. Net cost is
about $1 to 2 a month on top of any provider total in `COMPARISON.md`.

## Not covered / open items

- Terraform for the VM. Hetzner, Scaleway and Vultr have official providers
  and the VM, firewall and DNS record are about 40 lines; OVH VPS ordering
  is not Terraform-friendly.
- A GitHub Actions deploy workflow (`../CICD_DEPLOY_SKETCH.md` describes the
  AWS equivalent).
- Monitoring: only the dead-man's-switch above. Consider an external uptime
  check on `/healthz` (free tier of UptimeRobot or healthchecks.io).
- Log shipping: logs stay on the VM (`docker logs`, rotated).
- The Django backend is unaffected: same compose with the `backend/` image
  would work, but this plan targets the deployed Java stack only.
