package com.cadence.api.uploads;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.uploads.batch.UploadBatchFinalizer;
import com.cadence.api.uploads.batch.UploadJobLauncher;
import com.cadence.api.users.User;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {

	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("fit", "gpx", "tcx");

	private final UploadRepository uploadRepository;
	private final UploadBatchRepository uploadBatchRepository;
	private final ShoeRepository shoeRepository;
	private final UploadIngestService ingestService;
	private final UploadJobLauncher jobLauncher;
	private final UploadBatchFinalizer batchFinalizer;
	private final CadenceProperties properties;

	public UploadService(UploadRepository uploadRepository, UploadBatchRepository uploadBatchRepository,
			ShoeRepository shoeRepository, UploadIngestService ingestService, UploadJobLauncher jobLauncher,
			UploadBatchFinalizer batchFinalizer, CadenceProperties properties) {
		this.uploadRepository = uploadRepository;
		this.uploadBatchRepository = uploadBatchRepository;
		this.shoeRepository = shoeRepository;
		this.ingestService = ingestService;
		this.jobLauncher = jobLauncher;
		this.batchFinalizer = batchFinalizer;
		this.properties = properties;
	}

	public Upload createSingleUpload(User athlete, MultipartFile file, Double weightBeforeKg, Double weightAfterKg,
			Integer fluidsMl, String shoeId) throws IOException {
		Shoe shoe = null;
		if (shoeId != null && !shoeId.isBlank()) {
			shoe = shoeRepository.findByIdAndAthleteId(shoeId, athlete.getId())
					.orElseThrow(() -> new ValidationException("Unknown shoe '" + shoeId + "'.", "shoe_id"));
		}
		var staged = ingestService.readAndValidate(file.getOriginalFilename(), file.getBytes());
		Upload upload = ingestService.stageUpload(athlete, null, staged, weightBeforeKg, weightAfterKg, fluidsMl, shoe);
		if (upload.getStatus() == UploadStatus.QUEUED) {
			jobLauncher.launch(upload.getId());
		}
		return upload;
	}

	public UploadBatch createBatchUpload(User athlete, MultipartFile zipFile, OnDuplicate onDuplicate) throws IOException {
		List<UploadIngestService.StagedFile> entries = readZipEntries(zipFile);
		if (entries.isEmpty()) {
			throw new ValidationException("The archive contained no supported files.", "file");
		}

		UploadBatch batch = createBatch(athlete, zipFile.getOriginalFilename(), onDuplicate);
		List<Upload> children = new ArrayList<>();
		for (UploadIngestService.StagedFile entry : entries) {
			Upload upload = ingestService.stageUpload(athlete, batch, entry, null, null, null, null);
			children.add(upload);
		}
		for (Upload upload : children) {
			if (upload.getStatus() == UploadStatus.QUEUED) {
				jobLauncher.launch(upload.getId());
			}
		}
		// A batch whose files all settled at staging (e.g. every one a duplicate) launched
		// no jobs, so no job completion will ever finalize it - settle it here. No-ops when
		// jobs are still pending; harmless if the async jobs already finished and settled it.
		batchFinalizer.maybeFinalize(batch.getId());
		return uploadBatchRepository.findById(batch.getId()).orElse(batch);
	}

	// Plain save (not @Transactional) - this is a self-invoked private call, so a Spring AOP
	// transaction boundary here would be silently ineffective anyway; SimpleJpaRepository.save()
	// already runs in its own transaction, which is all a single insert needs.
	private UploadBatch createBatch(User athlete, String filename, OnDuplicate onDuplicate) {
		UploadBatch batch = new UploadBatch();
		batch.setAthlete(athlete);
		batch.setFilename(filename != null ? filename : "");
		batch.setOnDuplicate(onDuplicate != null ? onDuplicate : OnDuplicate.SKIP);
		batch.setStatus(UploadBatchStatus.PROCESSING);
		return uploadBatchRepository.save(batch);
	}

	private List<UploadIngestService.StagedFile> readZipEntries(MultipartFile zipFile) throws IOException {
		if (zipFile.getSize() > properties.uploads().maxUploadBytes()) {
			throw new ValidationException("The archive exceeds the maximum upload size.", "file");
		}
		List<UploadIngestService.StagedFile> staged = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(zipFile.getInputStream())) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory() || !isSupported(entry.getName())) {
					continue;
				}
				if (staged.size() >= properties.uploads().maxBatchFiles()) {
					throw new ValidationException("The archive exceeds the maximum file count.", "file");
				}
				byte[] bytes = zip.readAllBytes();
				staged.add(new UploadIngestService.StagedFile(baseName(entry.getName()), bytes));
			}
		}
		catch (java.util.zip.ZipException e) {
			throw new ValidationException("The archive could not be read.", "file");
		}
		return staged;
	}

	private boolean isSupported(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0) {
			return false;
		}
		return ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private String baseName(String entryName) {
		int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
		return slash < 0 ? entryName : entryName.substring(slash + 1);
	}

	public Upload getUpload(String id) {
		return uploadRepository.findById(id).orElseThrow(() -> new NotFoundException("No such upload."));
	}

	public UploadBatch getUploadBatch(String id) {
		return uploadBatchRepository.findById(id).orElseThrow(() -> new NotFoundException("No such upload batch."));
	}

	public List<Upload> getBatchChildren(String batchId) {
		return uploadRepository.findByBatchId(batchId);
	}
}
