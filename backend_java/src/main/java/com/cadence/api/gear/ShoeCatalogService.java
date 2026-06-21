package com.cadence.api.gear;

import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ShoeCatalogService {

	private final ShoeModelVersionRepository shoeModelVersionRepository;

	public ShoeCatalogService(ShoeModelVersionRepository shoeModelVersionRepository) {
		this.shoeModelVersionRepository = shoeModelVersionRepository;
	}

	public List<ShoeCatalogEntryResponse> search(String q) {
		String query = (q == null) ? "" : q;
		return shoeModelVersionRepository.search(query).stream()
				.map(smv -> new ShoeCatalogEntryResponse(
						smv.getId(),
						smv.getShoeModel().getManufacturer(),
						smv.getShoeModel().getModel(),
						smv.getVersion(),
						smv.getShoeModel().getManufacturer() + " " + smv.getShoeModel().getModel() + " " + smv.getVersion()))
				.toList();
	}
}
