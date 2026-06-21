package com.cadence.api.gear;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.gear.dto.ShoeUpdateRequest;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShoeService {

	private final ShoeRepository shoeRepository;
	private final ShoeModelVersionRepository shoeModelVersionRepository;

	public ShoeService(ShoeRepository shoeRepository, ShoeModelVersionRepository shoeModelVersionRepository) {
		this.shoeRepository = shoeRepository;
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
		shoe.setLimitKm(request.limitKm() != null ? request.limitKm() : 0);
		shoe.setImage(request.image());
		return shoeRepository.save(shoe);
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
		return (sm.getManufacturer() + " " + sm.getModel() + " " + smv.getVersion() + " " + colourway).trim();
	}
}
