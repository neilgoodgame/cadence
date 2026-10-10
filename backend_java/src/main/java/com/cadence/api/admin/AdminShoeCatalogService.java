package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminShoeCatalogEntryResponse;
import com.cadence.api.admin.dto.AdminShoeCatalogImportRequest;
import com.cadence.api.admin.dto.AdminShoeCatalogImportResponse;
import com.cadence.api.admin.dto.ShoeCatalogVersionUsage;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeModelVersionRepository.ShoeVersionUsage;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminShoeCatalogService {

	private final ShoeModelRepository shoeModelRepository;
	private final ShoeModelVersionRepository shoeModelVersionRepository;
	private final ShoeRepository shoeRepository;
	private final UserRepository userRepository;
	private final AdminAuditLogService auditLogService;

	public AdminShoeCatalogService(ShoeModelRepository shoeModelRepository,
			ShoeModelVersionRepository shoeModelVersionRepository, ShoeRepository shoeRepository,
			UserRepository userRepository, AdminAuditLogService auditLogService) {
		this.shoeModelRepository = shoeModelRepository;
		this.shoeModelVersionRepository = shoeModelVersionRepository;
		this.shoeRepository = shoeRepository;
		this.userRepository = userRepository;
		this.auditLogService = auditLogService;
	}

	// Read-only transaction, not just a plain method: toEntryResponse below lazily loads
	// ShoeModel.createdBy, which search()'s join-fetch doesn't cover - needs an open
	// Hibernate session to resolve, or it throws LazyInitializationException.
	@Transactional(readOnly = true)
	public List<AdminShoeCatalogEntryResponse> list(String q) {
		List<ShoeModelVersion> versions = shoeModelVersionRepository.search(q == null ? "" : q);
		Map<String, List<ShoeModelVersion>> byModel = new LinkedHashMap<>();
		for (ShoeModelVersion version : versions) {
			byModel.computeIfAbsent(version.getShoeModel().getId(), id -> new java.util.ArrayList<>()).add(version);
		}
		return byModel.values().stream().map(this::toEntryResponse).toList();
	}

	@Transactional
	public AdminShoeCatalogEntryResponse createOrAppend(String actingAdminId, String manufacturer, String model, String version) {
		User admin = userRepository.findById(actingAdminId).orElseThrow(() -> new NotFoundException("No such user."));
		ShoeModel existing = shoeModelRepository.findFirstByManufacturerIgnoreCaseAndModelIgnoreCase(manufacturer, model).orElse(null);
		if (existing != null) {
			appendVersionInternal(existing, version, admin);
			return toEntryResponse(existing);
		}
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(manufacturer);
		shoeModel.setModel(model);
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);

		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion(version);
		shoeModelVersionRepository.save(smv);

		auditLogService.logAdded(displayName(manufacturer, model, version), admin);
		return toEntryResponse(shoeModel);
	}

	@Transactional
	public AdminShoeCatalogEntryResponse appendVersion(String actingAdminId, String shoeModelId, String version) {
		User admin = userRepository.findById(actingAdminId).orElseThrow(() -> new NotFoundException("No such user."));
		ShoeModel shoeModel = shoeModelRepository.findById(shoeModelId)
				.orElseThrow(() -> new NotFoundException("No such shoe model."));
		appendVersionInternal(shoeModel, version, admin);
		return toEntryResponse(shoeModel);
	}

	/** Shared by {@link #createOrAppend} and {@link #appendVersion}, so the dedup check and
	 * audit-log write live in exactly one place. */
	private void appendVersionInternal(ShoeModel shoeModel, String version, User admin) {
		if (shoeModelVersionRepository.existsByShoeModelIdAndVersionIgnoreCase(shoeModel.getId(), version)) {
			throw new ConflictException("This shoe model already has that version.");
		}
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion(version);
		shoeModelVersionRepository.save(smv);
		auditLogService.logAdded(displayName(shoeModel.getManufacturer(), shoeModel.getModel(), version), admin);
	}

	/** Bulk create-or-append, for an admin uploading a CSV (e.g. a Strava shoe-rotation export)
	 * from the catalog screen - entries are parsed client-side; this just applies them the same
	 * way {@link #createOrAppend} does, except a duplicate version is silently skipped (counted,
	 * not rejected) rather than aborting the whole batch, so re-uploading the same file is
	 * idempotent. */
	@Transactional
	public AdminShoeCatalogImportResponse importEntries(
			String actingAdminId, List<AdminShoeCatalogImportRequest.Entry> entries) {
		User admin = userRepository.findById(actingAdminId).orElseThrow(() -> new NotFoundException("No such user."));
		int modelsCreated = 0;
		int versionsAdded = 0;
		int skipped = 0;
		for (AdminShoeCatalogImportRequest.Entry entry : entries) {
			String version = entry.version() != null ? entry.version() : "";
			ShoeModel shoeModel = shoeModelRepository
					.findFirstByManufacturerIgnoreCaseAndModelIgnoreCase(entry.manufacturer(), entry.model())
					.orElse(null);
			boolean isNewModel = shoeModel == null;
			if (isNewModel) {
				shoeModel = new ShoeModel();
				shoeModel.setManufacturer(entry.manufacturer());
				shoeModel.setModel(entry.model());
				shoeModel.setCreatedBy(admin);
				shoeModelRepository.save(shoeModel);
			}
			if (shoeModelVersionRepository.existsByShoeModelIdAndVersionIgnoreCase(shoeModel.getId(), version)) {
				skipped++;
				continue;
			}
			ShoeModelVersion smv = new ShoeModelVersion();
			smv.setShoeModel(shoeModel);
			smv.setVersion(version);
			shoeModelVersionRepository.save(smv);
			auditLogService.logAdded(displayName(entry.manufacturer(), entry.model(), version), admin);
			if (isNewModel) {
				modelsCreated++;
			}
			versionsAdded++;
		}
		return new AdminShoeCatalogImportResponse(modelsCreated, versionsAdded, skipped);
	}

	@Transactional
	public void delete(String actingAdminId, String shoeModelId) {
		User admin = userRepository.findById(actingAdminId).orElseThrow(() -> new NotFoundException("No such user."));
		ShoeModel shoeModel = shoeModelRepository.findById(shoeModelId)
				.orElseThrow(() -> new NotFoundException("No such shoe model."));
		if (shoeRepository.existsByShoeModelVersionShoeModelId(shoeModelId)) {
			throw new ConflictException("This shoe model is still in use by athletes' gear.");
		}
		String description = shoeModel.getManufacturer() + " " + shoeModel.getModel();
		// shoe_model_version.shoe_model_id is ON DELETE RESTRICT, not CASCADE (V6__gear_catalog.sql) -
		// unlike the Django side, versions must be deleted explicitly before the model row.
		shoeModelVersionRepository.deleteAll(shoeModelVersionRepository.findByShoeModelId(shoeModelId));
		shoeModelRepository.delete(shoeModel);
		auditLogService.logRemoved(description, admin);
	}

	private AdminShoeCatalogEntryResponse toEntryResponse(ShoeModel shoeModel) {
		List<ShoeModelVersion> versions = shoeModelVersionRepository.findByShoeModelId(shoeModel.getId()).stream()
				.sorted(Comparator.comparing(ShoeModelVersion::getVersion))
				.toList();
		return buildResponse(shoeModel, versions);
	}

	private AdminShoeCatalogEntryResponse toEntryResponse(List<ShoeModelVersion> versionsForModel) {
		ShoeModel shoeModel = versionsForModel.get(0).getShoeModel();
		return buildResponse(shoeModel, versionsForModel);
	}

	// usageCount counts every Shoe referencing that version regardless of its retired flag,
	// matching the delete-block check above exactly - it should read as "why can't I delete
	// this", not "how many *active* shoes use it".
	private AdminShoeCatalogEntryResponse buildResponse(ShoeModel shoeModel, List<ShoeModelVersion> versions) {
		Map<String, Long> usageByVersionId = shoeModelVersionRepository.countUsageByShoeModelId(shoeModel.getId())
				.stream()
				.collect(Collectors.toMap(ShoeVersionUsage::getShoeModelVersionId, ShoeVersionUsage::getUsageCount));
		List<ShoeCatalogVersionUsage> versionUsages = versions.stream()
				.map(v -> new ShoeCatalogVersionUsage(v.getVersion(), usageByVersionId.getOrDefault(v.getId(), 0L)))
				.toList();
		String addedBy = shoeModel.getCreatedBy() != null ? shoeModel.getCreatedBy().getName() : null;
		return new AdminShoeCatalogEntryResponse(
				shoeModel.getId(), shoeModel.getManufacturer(), shoeModel.getModel(), versionUsages, addedBy);
	}

	private String displayName(String manufacturer, String model, String version) {
		if (version == null || version.isBlank()) {
			return manufacturer + " " + model;
		}
		return manufacturer + " " + model + " v" + version;
	}
}
