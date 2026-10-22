package com.cadence.api.garmin;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.garmin.GarminApiClient.ActivitySummary;
import com.cadence.api.garmin.dto.GarminSyncRequest;
import com.cadence.api.uploads.OnDuplicate;
import com.cadence.api.uploads.UploadBatch;
import com.cadence.api.uploads.UploadIngestService.StagedFile;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadService.SessionDetails;
import com.cadence.api.users.User;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Pull-on-request sync from the athlete's Garmin Connect via their Personal API key. Walks the
 * window oldest-first and takes at most {@code limit} not-yet-synced activities, so repeated
 * syncs make steady progress through a long backlog instead of re-fetching the newest ones.
 * Garmin activity ids already pulled are skipped before downloading (see
 * {@code garmin_synced_activity}); everything fetched goes through the same batch upload pipeline
 * as a manual .zip, so identical files and stub files are handled exactly as there. Each attempt
 * is recorded as a {@link GarminSyncRun}.
 */
@Service
public class GarminSyncService {

	private static final Logger log = LoggerFactory.getLogger(GarminSyncService.class);
	/** How far back the very first sync looks when no window is given. */
	static final int FIRST_SYNC_DAYS = 30;
	/** Runs kept per athlete; older ones are pruned (the synced-id records are not). */
	static final int RUNS_KEPT = 50;
	/** Courtesy pause between downloads - Garmin documents no rate limit. */
	private static final long DOWNLOAD_PAUSE_MS = 250;

	public record Result(GarminSyncRun run, UploadBatch batch) {
	}

	private final GarminConnectionRepository repository;
	private final GarminSyncRunRepository runRepository;
	private final GarminSyncedActivityRepository syncedRepository;
	private final GarminKeyCipher cipher;
	private final GarminApiClient client;
	private final UploadService uploadService;
	private final CadenceProperties properties;

	public GarminSyncService(GarminConnectionRepository repository, GarminSyncRunRepository runRepository,
			GarminSyncedActivityRepository syncedRepository, GarminKeyCipher cipher, GarminApiClient client,
			UploadService uploadService, CadenceProperties properties) {
		this.repository = repository;
		this.runRepository = runRepository;
		this.syncedRepository = syncedRepository;
		this.cipher = cipher;
		this.client = client;
		this.uploadService = uploadService;
		this.properties = properties;
	}

	// Deliberately not @Transactional: this makes slow network calls, and holding a DB
	// transaction open across them would pin a pooled connection for the duration.
	public Result sync(User owner, GarminSyncRequest request) {
		GarminConnection connection = repository.findByOwnerId(owner.getId())
				.orElseThrow(() -> new NotFoundException("No Garmin connection is set up."));
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (connection.getKeyExpiresOn() != null && connection.getKeyExpiresOn().isBefore(today)) {
			throw new ConflictException("Your Garmin API key expired on " + connection.getKeyExpiresOn()
					+ ". Create a new one in Garmin Connect and replace it in Preferences.");
		}

		boolean latestOnly = request.isLatest();
		if (!latestOnly && request.hasSessionDetails()) {
			throw new ValidationException("Session details can only be given when syncing the latest activity.", "latest");
		}
		int max = GarminConnection.MAX_SYNC_LIMIT;
		int limit = request.limit() == null ? connection.getSyncLimit() : request.limit();
		if (limit < 1 || limit > max) {
			throw new ValidationException("limit must be between 1 and " + max + ".", "limit");
		}
		LocalDate since = request.since();
		if (since != null && since.isAfter(today)) {
			throw new ValidationException("since cannot be in the future.", "since");
		}
		// Resolved before the run is recorded, so an unknown shoe is a plain 400 and leaves no run behind.
		SessionDetails details = latestOnly ? new SessionDetails(request.weightBeforeKg(), request.weightAfterKg(),
				request.fluidsMl(), uploadService.resolveShoe(owner, request.shoeId())) : null;
		LocalDate windowStart = since != null ? since
				: connection.getLastSyncedAt() != null
						? connection.getLastSyncedAt().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
						: today.minusDays(FIRST_SYNC_DAYS);

		GarminSyncRun run = new GarminSyncRun();
		run.setOwner(owner);
		run.setStartedAt(Instant.now());
		run = runRepository.save(run);
		try {
			return doSync(owner, connection, run, limit, windowStart, latestOnly, details);
		}
		catch (ApiException e) {
			// Record why it failed (a message we wrote - never the key), then let the caller see it too.
			run.setError(e.getMessage());
			run.setFinishedAt(Instant.now());
			runRepository.save(run);
			prune(owner.getId());
			throw e;
		}
	}

	private Result doSync(User owner, GarminConnection connection, GarminSyncRun run, int limit, LocalDate windowStart,
			boolean latestOnly, SessionDetails details) {
		String token = client.exchangeKey(cipher.decrypt(connection.getApiKeyEncrypted(), owner.getId()));
		List<ActivitySummary> window = new ArrayList<>(
				latestOnly ? client.latest(token) : client.listSince(token, windowStart));
		window.sort(Comparator.comparing(ActivitySummary::startTime));

		Set<Long> seen = window.isEmpty() ? Set.of()
				: syncedRepository.findSeenIds(owner.getId(),
						window.stream().map(ActivitySummary::activityId).collect(Collectors.toList()));
		List<ActivitySummary> candidates = window.stream().filter(a -> !seen.contains(a.activityId())).toList();
		int take = latestOnly ? 1 : limit;
		boolean truncated = candidates.size() > take;
		List<ActivitySummary> taken = truncated ? candidates.subList(0, take) : candidates;

		List<StagedFile> staged = new ArrayList<>();
		List<ActivitySummary> stagedFrom = new ArrayList<>();
		int failed = 0;
		for (ActivitySummary summary : taken) {
			try {
				List<StagedFile> files = extractFiles(client.downloadZip(token, summary.activityId()));
				if (!files.isEmpty()) {
					staged.addAll(files);
					stagedFrom.add(summary);
				}
			}
			catch (ApiException e) {
				if (e.getStatus() != HttpStatus.BAD_GATEWAY) {
					throw e; // a rejected key etc. won't get better on the next activity
				}
				failed++;
				log.warn("Garmin activity {} could not be fetched: {}", summary.activityId(), e.getMessage());
			}
			pause();
		}
		if (!taken.isEmpty() && staged.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_GATEWAY,
					"None of the " + taken.size() + " activities could be downloaded from Garmin.");
		}

		UploadBatch batch = staged.isEmpty() ? null
				: uploadService.createBatchUpload(owner, staged, "garmin-sync", OnDuplicate.SKIP, details);
		for (ActivitySummary summary : stagedFrom) {
			try {
				syncedRepository.save(new GarminSyncedActivity(owner, summary.activityId(), summary.startTime(), run.getId()));
			}
			catch (DataIntegrityViolationException e) {
				// A concurrent sync recorded the same id first; the batch pipeline already deduped the file.
			}
		}

		// Only move the marker when nothing was lost: a failed download must be retried, and
		// recorded ids plus the batch pipeline's duplicate handling make re-fetching the rest harmless.
		// A latest-only sync never moves it: older activities may still be waiting in the window.
		if (failed == 0 && !latestOnly) {
			connection.setLastSyncedAt(truncated ? taken.get(taken.size() - 1).startTime() : Instant.now());
			repository.save(connection);
		}

		run.setFound(window.size());
		run.setAlreadySynced(window.size() - candidates.size());
		run.setRequested(taken.size());
		run.setFailed(failed);
		run.setTruncated(truncated);
		run.setBatchId(batch != null ? batch.getId() : null);
		run.setFinishedAt(Instant.now());
		runRepository.save(run);
		prune(owner.getId());
		return new Result(run, batch);
	}

	private void prune(String ownerId) {
		List<GarminSyncRun> runs = runRepository.findByOwnerIdOrderByStartedAtDesc(ownerId);
		if (runs.size() > RUNS_KEPT) {
			runRepository.deleteAll(runs.subList(RUNS_KEPT, runs.size()));
		}
	}

	/** The activity's .fit file(s) out of Garmin's download ZIP. */
	private List<StagedFile> extractFiles(byte[] zip) {
		long maxBytes = properties.uploads().maxUploadBytes();
		List<StagedFile> files = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName();
				if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".fit")) {
					continue;
				}
				byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
				if (bytes.length > maxBytes) {
					throw new ApiException(HttpStatus.BAD_GATEWAY, "A Garmin file exceeded the maximum upload size.");
				}
				int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
				files.add(new StagedFile(slash < 0 ? name : name.substring(slash + 1), bytes));
			}
		}
		catch (IOException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "A file from Garmin could not be read.");
		}
		return files;
	}

	private static void pause() {
		try {
			Thread.sleep(DOWNLOAD_PAUSE_MS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
