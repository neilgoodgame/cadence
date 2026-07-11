package com.cadence.api.gear;

import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ShoeCatalogService {

	private final ShoeModelRepository shoeModelRepository;
	private final ShoeModelVersionRepository shoeModelVersionRepository;
	private final UserService userService;

	public ShoeCatalogService(
			ShoeModelRepository shoeModelRepository,
			ShoeModelVersionRepository shoeModelVersionRepository,
			UserService userService) {
		this.shoeModelRepository = shoeModelRepository;
		this.shoeModelVersionRepository = shoeModelVersionRepository;
		this.userService = userService;
	}

	public List<ShoeCatalogEntryResponse> search(String q) {
		String query = (q == null) ? "" : q;
		return shoeModelVersionRepository.search(query).stream()
				.map(this::toResponse)
				.toList();
	}

	public ShoeCatalogEntryResponse createShoeModel(String createdById, String manufacturer, String model, String version) {
		User createdBy = userService.getById(createdById);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(manufacturer);
		shoeModel.setModel(model);
		shoeModel.setCreatedBy(createdBy);
		shoeModelRepository.save(shoeModel);

		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion(version != null ? version : "");
		shoeModelVersionRepository.save(smv);

		return toResponse(smv);
	}

	private ShoeCatalogEntryResponse toResponse(ShoeModelVersion smv) {
		return new ShoeCatalogEntryResponse(
				smv.getId(),
				smv.getShoeModel().getManufacturer(),
				smv.getShoeModel().getModel(),
				smv.getVersion(),
				displayName(smv.getShoeModel().getManufacturer(), smv.getShoeModel().getModel(), smv.getVersion()));
	}

	private String displayName(String manufacturer, String model, String version) {
		if (version == null || version.isBlank()) {
			return manufacturer + " " + model;
		}
		return manufacturer + " " + model + " " + version;
	}
}
