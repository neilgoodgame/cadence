package com.cadence.api.gear;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShoeCatalogController {

	private final ShoeCatalogService shoeCatalogService;

	public ShoeCatalogController(ShoeCatalogService shoeCatalogService) {
		this.shoeCatalogService = shoeCatalogService;
	}

	@GetMapping("/v1/gear/shoe-catalog")
	public DataListResponse<ShoeCatalogEntryResponse> search(@RequestParam(required = false) String q) {
		return new DataListResponse<>(shoeCatalogService.search(q));
	}
}
