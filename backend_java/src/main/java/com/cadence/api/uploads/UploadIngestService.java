package com.cadence.api.uploads;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Shoe;
import com.cadence.api.users.User;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staging for one uploaded file: validates the extension, hashes the bytes for dedup, writes
 * them to disk, and creates the {@link Upload} row - all the work that must finish (and commit)
 * before {@link com.cadence.api.uploads.batch.UploadJobLauncher} is told to start processing it.
 * Deliberately not the thing that launches the job: launching from inside this method's own
 * transaction would race the background thread against a not-yet-committed insert.
 */
@Service
public class UploadIngestService {

	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("fit", "gpx", "tcx");

	private final UploadRepository uploadRepository;
	private final CadenceProperties properties;

	public UploadIngestService(UploadRepository uploadRepository, CadenceProperties properties) {
		this.uploadRepository = uploadRepository;
		this.properties = properties;
	}

	public record StagedFile(String filename, byte[] bytes) {
	}

	public StagedFile readAndValidate(String filename, byte[] bytes) {
		if (filename == null || extensionOf(filename).isEmpty()) {
			throw new ValidationException("Unsupported file type.", "file");
		}
		String extension = extensionOf(filename);
		if (!ALLOWED_EXTENSIONS.contains(extension)) {
			throw new ValidationException("Unsupported file type: ." + extension, "file");
		}
		if (bytes.length > properties.uploads().maxUploadBytes()) {
			throw new ValidationException("File exceeds the maximum upload size.", "file");
		}
		return new StagedFile(filename, bytes);
	}

	/** {@code null} batch/weight/fluids/shoe args are all valid - this single-file path is also used per-entry from a batch zip. */
	@Transactional
	public Upload stageUpload(User athlete, UploadBatch batch, StagedFile file,
			Double weightBeforeKg, Double weightAfterKg, Integer fluidsMl, Shoe shoe) {
		String hash = sha256Hex(file.bytes());

		Optional<Upload> existingReady = uploadRepository
				.findFirstByAthleteIdAndFileHashAndStatusAndActivityIsNotNullOrderByReceivedAtDesc(athlete.getId(), hash, UploadStatus.READY);
		if (existingReady.isPresent() && (batch == null || batch.getOnDuplicate() == OnDuplicate.SKIP)) {
			Upload duplicate = new Upload();
			duplicate.setAthlete(athlete);
			duplicate.setBatch(batch);
			duplicate.setFilename(file.filename());
			duplicate.setFileHash(hash);
			duplicate.setStatus(UploadStatus.DUPLICATE);
			duplicate.setActivity(existingReady.get().getActivity());
			duplicate.setProgress(1.0);
			duplicate.setCompletedAt(Instant.now());
			return uploadRepository.save(duplicate);
		}

		String storedPath = storeFile(athlete.getId(), hash, file.filename(), file.bytes());

		Upload upload = new Upload();
		upload.setAthlete(athlete);
		upload.setBatch(batch);
		upload.setFilename(file.filename());
		upload.setFileHash(hash);
		upload.setStoredPath(storedPath);
		upload.setStatus(UploadStatus.QUEUED);
		upload.setWeightBeforeKg(weightBeforeKg);
		upload.setWeightAfterKg(weightAfterKg);
		upload.setFluidsMl(fluidsMl);
		upload.setShoe(shoe);
		return uploadRepository.save(upload);
	}

	private String storeFile(String athleteId, String hash, String filename, byte[] bytes) {
		try {
			Path dir = Path.of(properties.uploads().mediaRoot(), athleteId);
			Files.createDirectories(dir);
			String safeName = hash + "_" + System.nanoTime() + "_" + sanitize(filename);
			Path target = dir.resolve(safeName);
			Files.write(target, bytes);
			return athleteId + "/" + safeName;
		}
		catch (IOException e) {
			throw new ValidationException("Could not store the uploaded file.", "file");
		}
	}

	private String sanitize(String filename) {
		return filename.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
