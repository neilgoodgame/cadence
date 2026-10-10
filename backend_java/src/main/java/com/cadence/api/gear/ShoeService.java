package com.cadence.api.gear;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeImportRequest;
import com.cadence.api.gear.dto.ShoeImportResponse;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.gear.dto.ShoeUpdateRequest;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShoeService {

	private final ShoeRepository shoeRepository;
	private final ShoeModelRepository shoeModelRepository;
	private final ShoeModelVersionRepository shoeModelVersionRepository;

	public ShoeService(ShoeRepository shoeRepository, ShoeModelRepository shoeModelRepository,
			ShoeModelVersionRepository shoeModelVersionRepository) {
		this.shoeRepository = shoeRepository;
		this.shoeModelRepository = shoeModelRepository;
		this.shoeModelVersionRepository = shoeModelVersionRepository;
	}

	public List<Shoe> listShoes(String athleteId) {
		return shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(athleteId);
	}

	public ShoeResponse toResponse(Shoe shoe) {
		ShoeModelVersion smv = shoe.getShoeModelVersion();
		ShoeModel sm = smv.getShoeModel();
		return new ShoeResponse(shoe.getId(), shoe.getAthlete().getId(), smv.getId(), sm.getManufacturer(),
				sm.getModel(), smv.getVersion(), shoe.getColourway(), shoe.getName(), shoe.getImage(), shoe.getRole(),
				shoe.getKm(), shoe.getLimitKm(), shoe.getSince());
	}

	@Transactional
	public Shoe createShoe(User athlete, ShoeCreateRequest request) {
		ShoeModelVersion smv = shoeModelVersionRepository.findByIdWithShoeModel(request.shoeModelVersionId())
				.orElseThrow(() -> new ValidationException("No such shoe model version.", "shoe_model_version_id"));
		String name = request.name();
		if (name == null || name.isBlank()) {
			name = composeDefaultName(smv, request.colourway());
		}
		if (shoeRepository.existsByAthleteIdAndNameIgnoreCase(athlete.getId(), name)) {
			throw new ConflictException("You already have a pair of shoes named \"" + name + "\".");
		}
		Shoe shoe = new Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(smv);
		shoe.setColourway(request.colourway());
		shoe.setName(name);
		shoe.setLimitKm(request.limitKm() != null ? request.limitKm() : athlete.getDefaultShoeLimitKm());
		shoe.setImage(request.image());
		return shoeRepository.save(shoe);
	}

	/** Bulk-add gear from a CSV (e.g. a Strava shoe-rotation export), parsed client-side.
	 *
	 * <p>A non-admin's import only adds pairs whose manufacturer+model+version already exists in
	 * the shared catalog - rows that don't match are skipped rather than silently polluting the
	 * shared catalog with whatever an arbitrary CSV happens to contain. An admin's import may
	 * also create the missing catalog model/version first, same as the admin shoe-catalog
	 * screen's own bulk import ({@code AdminShoeCatalogService.importEntries}) - {@code isAdmin}
	 * must be the real signed-in principal's admin status, never a delegated athlete's, matching
	 * {@code AccessGuard.requireAdmin}'s own rule.
	 *
	 * <p>Idempotent re-upload: a (shoeModelVersion, colourway) pair already present in the
	 * athlete's gear is skipped too, so re-importing the same file twice doesn't create
	 * duplicates. */
	@Transactional
	public ShoeImportResponse importShoes(User athlete, List<ShoeImportRequest.Entry> entries, boolean isAdmin) {
		int shoesCreated = 0;
		int catalogModelsCreated = 0;
		int catalogVersionsCreated = 0;
		int skippedNoCatalogMatch = 0;
		int skippedAlreadyInGear = 0;

		for (ShoeImportRequest.Entry entry : entries) {
			String manufacturer = entry.manufacturer();
			String model = entry.model();
			String version = entry.version() != null ? entry.version() : "";
			String colourway = entry.colourway() != null ? entry.colourway() : "";

			ShoeModel shoeModel = shoeModelRepository
					.findFirstByManufacturerIgnoreCaseAndModelIgnoreCase(manufacturer, model)
					.orElse(null);
			boolean isNewModel = shoeModel == null;
			ShoeModelVersion smv = shoeModel == null
					? null
					: shoeModelVersionRepository.findFirstByShoeModelIdAndVersionIgnoreCase(shoeModel.getId(), version)
							.orElse(null);

			if (smv == null) {
				if (!isAdmin) {
					skippedNoCatalogMatch++;
					continue;
				}
				if (isNewModel) {
					shoeModel = new ShoeModel();
					shoeModel.setManufacturer(manufacturer);
					shoeModel.setModel(model);
					shoeModel.setCreatedBy(athlete);
					shoeModelRepository.save(shoeModel);
					catalogModelsCreated++;
				}
				smv = new ShoeModelVersion();
				smv.setShoeModel(shoeModel);
				smv.setVersion(version);
				shoeModelVersionRepository.save(smv);
				catalogVersionsCreated++;
			}

			if (shoeRepository.existsByAthleteIdAndShoeModelVersionIdAndColourwayIgnoreCase(
					athlete.getId(), smv.getId(), colourway)) {
				skippedAlreadyInGear++;
				continue;
			}

			String baseName = composeImportName(manufacturer, model, version, colourway);
			String name = baseName;
			int disambiguator = 2;
			while (shoeRepository.existsByAthleteIdAndNameIgnoreCase(athlete.getId(), name)) {
				name = baseName + " (" + disambiguator + ")";
				disambiguator++;
			}

			Shoe shoe = new Shoe();
			shoe.setAthlete(athlete);
			shoe.setShoeModelVersion(smv);
			shoe.setColourway(colourway);
			shoe.setName(name);
			shoe.setKm(entry.distanceKm() != null ? (int) Math.round(entry.distanceKm()) : 0);
			shoe.setLimitKm(athlete.getDefaultShoeLimitKm());
			shoeRepository.save(shoe);
			shoesCreated++;
		}

		return new ShoeImportResponse(
				shoesCreated, catalogModelsCreated, catalogVersionsCreated, skippedNoCatalogMatch, skippedAlreadyInGear);
	}

	public Shoe getShoe(String id) {
		return shoeRepository.findByIdWithCatalog(id).orElseThrow(() -> new NotFoundException("No such shoe."));
	}

	/**
	 * Reloads, mutates, saves, and maps to the response DTO within a single transaction - see
	 * {@code SharingService.updateRoleAndRespond} for why merging a detached entity and mapping
	 * the result afterwards isn't safe even for associations the update itself didn't touch.
	 */
	@Transactional
	public ShoeResponse updateShoeAndRespond(String id, ShoeUpdateRequest request) {
		Shoe shoe = getShoe(id);
		if (request.name() != null) {
			if (shoeRepository.existsByAthleteIdAndNameIgnoreCaseAndIdNot(shoe.getAthlete().getId(), request.name(), shoe.getId())) {
				throw new ConflictException("You already have a pair of shoes named \"" + request.name() + "\".");
			}
			shoe.setName(request.name());
		}
		if (request.limitKm() != null) {
			shoe.setLimitKm(request.limitKm());
		}
		if (request.km() != null) {
			shoe.setKm(request.km());
		}
		if (request.image() != null) {
			shoe.setImage(request.image());
		}
		if (request.retired() != null) {
			shoe.setRetired(request.retired());
		}
		Shoe saved = shoeRepository.save(shoe);
		return toResponse(saved);
	}

	@Transactional
	public void deleteShoe(String id) {
		shoeRepository.deleteById(id);
	}

	private String composeDefaultName(ShoeModelVersion smv, String colourway) {
		ShoeModel sm = smv.getShoeModel();
		return composeImportName(sm.getManufacturer(), sm.getModel(), smv.getVersion(), colourway);
	}

	// Unlike composeDefaultName above (which assumes a single-add form's version/colourway are
	// both almost always present), an import's version/colourway are routinely blank - joining
	// only the non-blank parts avoids a doubled space in the middle of the name that trim()
	// alone wouldn't catch.
	private static String composeImportName(String manufacturer, String model, String version, String colourway) {
		StringBuilder name = new StringBuilder(manufacturer).append(' ').append(model);
		if (!version.isBlank()) {
			name.append(' ').append(version);
		}
		if (!colourway.isBlank()) {
			name.append(' ').append(colourway);
		}
		return name.toString();
	}
}
