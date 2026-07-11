package com.cadence.api.gear;

import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.gear.dto.ShoeCatalogEntryResponse;
import com.cadence.api.gear.dto.ShoeModelCreateRequest;
import com.cadence.api.security.AuthContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

	@PostMapping("/v1/gear/shoe-catalog")
	@ResponseStatus(HttpStatus.CREATED)
	public ShoeCatalogEntryResponse createShoeModel(@Valid @RequestBody ShoeModelCreateRequest request) {
		String sub = AuthContextHolder.get().sub();
		return shoeCatalogService.createShoeModel(sub, request.manufacturer(), request.model(), request.version());
	}
}
