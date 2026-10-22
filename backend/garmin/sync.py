"""Pull-on-request sync from the athlete's Garmin Connect via their Personal API key. Walks the
window oldest-first and takes at most ``limit`` not-yet-synced activities, so repeated syncs make
steady progress through a long backlog instead of re-fetching the newest ones. Garmin activity
ids already pulled are skipped before downloading (see ``GarminSyncedActivity``); everything
fetched goes through the same batch upload pipeline as a manual .zip, so identical files and stub
files are handled exactly as there. Each attempt is recorded as a ``GarminSyncRun``. Mirrors ``GarminSyncService`` in backend_java.
"""

import io
import logging
import time
import zipfile
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta

from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, NotFound, ValidationError

from accounts.models import User
from core.exceptions import ConflictError
from gear.models import Shoe
from uploads.models import UploadBatch
from uploads.services import MAX_BATCH_BYTES, create_batch_from_files

from . import client, crypto
from .models import MAX_SYNC_LIMIT, GarminConnection, GarminSyncedActivity, GarminSyncRun

logger = logging.getLogger(__name__)

# How far back the very first sync looks when no window is given.
FIRST_SYNC_DAYS = 30
# Runs kept per athlete; older ones are pruned (the synced-id records are not).
RUNS_KEPT = 50
# Courtesy pause between downloads - Garmin documents no rate limit.
DOWNLOAD_PAUSE_S = 0.25
# Per-session details a latest-only sync may carry, as for a single-file import.
SESSION_FIELDS = ("weight_before_kg", "weight_after_kg", "fluids_ml", "shoe_id")


@dataclass(frozen=True)
class SyncResult:
    run: GarminSyncRun
    batch: UploadBatch | None


def sync(
    owner: User,
    limit: int | None,
    since: date | None,
    latest: bool = False,
    session: dict | None = None,
) -> SyncResult:
    connection = GarminConnection.objects.filter(owner=owner).first()
    if connection is None:
        raise NotFound("No Garmin connection is set up.")
    today = datetime.now(UTC).date()
    if connection.key_expires_on is not None and connection.key_expires_on < today:
        raise ConflictError(
            f"Your Garmin API key expired on {connection.key_expires_on}. "
            "Create a new one in Garmin Connect and replace it in Preferences."
        )

    session = {k: v for k, v in (session or {}).items() if v not in (None, "")}
    if session and not latest:
        raise ValidationError({"latest": "Session details can only be given when syncing the latest activity."})
    limit = connection.sync_limit if limit is None else limit
    if not 1 <= limit <= MAX_SYNC_LIMIT:
        raise ValidationError({"limit": f"limit must be between 1 and {MAX_SYNC_LIMIT}."})
    # Checked before the run is recorded, so an unknown shoe is a plain 400 and leaves no run behind.
    shoe_id = session.get("shoe_id")
    if shoe_id and not Shoe.objects.filter(pk=shoe_id, athlete_id=owner.id).exists():
        raise ValidationError({"shoe_id": f"Unknown shoe '{shoe_id}'."})
    if since is not None and since > today:
        raise ValidationError({"since": "since cannot be in the future."})
    if since is not None:
        window_start = since
    elif connection.last_synced_at is not None:
        window_start = connection.last_synced_at.astimezone(UTC).date() - timedelta(days=1)
    else:
        window_start = today - timedelta(days=FIRST_SYNC_DAYS)

    run = GarminSyncRun.objects.create(owner=owner, started_at=timezone.now())
    try:
        return _do_sync(owner, connection, run, limit, window_start, latest, session)
    except APIException as exc:
        # Record why it failed (a message we wrote - never the key), then let the caller see it too.
        run.error = str(exc.detail)
        run.finished_at = timezone.now()
        run.save()
        _prune(owner)
        raise
    except BaseException:
        # A worker timeout or shutdown (SystemExit) mid-sync. Close the run so the history
        # doesn't show it in flight forever.
        run.error = "The sync was interrupted before it finished. Sync again to continue."
        run.finished_at = timezone.now()
        run.save()
        raise


def _do_sync(
    owner: User,
    connection: GarminConnection,
    run: GarminSyncRun,
    limit: int,
    window_start: date,
    latest: bool,
    session: dict,
) -> SyncResult:
    token = client.exchange_key(crypto.decrypt(connection.api_key_encrypted, owner.id))
    found = client.latest(token) if latest else client.list_since(token, window_start)
    window = sorted(found, key=lambda a: a.start_time)
    seen = set(
        GarminSyncedActivity.objects.filter(
            owner=owner, garmin_activity_id__in=[a.activity_id for a in window]
        ).values_list("garmin_activity_id", flat=True)
    )
    candidates = [a for a in window if a.activity_id not in seen]
    take = 1 if latest else limit
    truncated = len(candidates) > take
    taken = candidates[:take]

    staged: list[tuple[str, bytes]] = []
    staged_from: list[client.ActivitySummary] = []
    failed = 0
    for summary in taken:
        try:
            files = _extract_files(client.download_zip(token, summary.activity_id))
            if files:
                staged.extend(files)
                staged_from.append(summary)
        except client.GarminUpstreamError as exc:
            # A rejected key (422) is not caught here - it won't get better on the next activity.
            failed += 1
            logger.warning("Garmin activity %s could not be fetched: %s", summary.activity_id, exc.detail)
        time.sleep(DOWNLOAD_PAUSE_S)
    if taken and not staged:
        raise client.GarminUpstreamError(f"None of the {len(taken)} activities could be downloaded from Garmin.")

    batch = create_batch_from_files(owner.id, "garmin-sync", "skip", staged, session) if staged else None
    for summary in staged_from:
        try:
            with transaction.atomic():
                GarminSyncedActivity.objects.create(
                    owner=owner, garmin_activity_id=summary.activity_id, start_time=summary.start_time, run=run
                )
        except IntegrityError:
            pass  # a concurrent sync recorded the same id first; the batch pipeline already deduped the file

    # Only move the marker when nothing was lost: a failed download must be retried, and recorded
    # ids plus the batch pipeline's duplicate handling make re-fetching the rest harmless. A
    # latest-only sync never moves it: older activities may still be waiting in the window.
    if failed == 0 and not latest:
        connection.last_synced_at = taken[-1].start_time if truncated else timezone.now()
        connection.save(update_fields=["last_synced_at", "updated"])

    run.found = len(window)
    run.already_synced = len(window) - len(candidates)
    run.requested = len(taken)
    run.failed = failed
    run.truncated = truncated
    run.batch = batch
    run.finished_at = timezone.now()
    run.save()
    _prune(owner)
    return SyncResult(run, batch)


def _prune(owner: User) -> None:
    stale = list(GarminSyncRun.objects.filter(owner=owner).order_by("-started_at").values_list("id", flat=True))[
        RUNS_KEPT:
    ]
    if stale:
        GarminSyncRun.objects.filter(id__in=stale).delete()


def _extract_files(data: bytes) -> list[tuple[str, bytes]]:
    """The activity's .fit file(s) out of Garmin's download ZIP."""
    files: list[tuple[str, bytes]] = []
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in archive.infolist():
                if info.is_dir() or not info.filename.lower().endswith(".fit"):
                    continue
                with archive.open(info) as handle:
                    content = handle.read(MAX_BATCH_BYTES + 1)
                if len(content) > MAX_BATCH_BYTES:
                    raise client.GarminUpstreamError("A Garmin file exceeded the maximum upload size.")
                files.append((info.filename.replace("\\", "/").rsplit("/", 1)[-1], content))
    except zipfile.BadZipFile as exc:
        raise client.GarminUpstreamError("A file from Garmin could not be read.") from exc
    return files
