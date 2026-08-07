package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminShoeCatalogCreateRequest;
import com.cadence.api.admin.dto.AdminShoeCatalogEntryResponse;
import com.cadence.api.admin.dto.AdminShoeVersionCreateRequest;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminShoeCatalogController {

	private final AdminShoeCatalogService service;
	private final AccessGuard accessGuard;

	public AdminShoeCatalogController(AdminShoeCatalogService service, AccessGuard accessGuard) {
		this.service = service;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/admin/shoe-catalog")
	public DataListResponse<AdminShoeCatalogEntryResponse> list(@RequestParam(required = false) String q) {
		accessGuard.requireAdmin();
		return new DataListResponse<>(service.list(q));
	}

	@PostMapping("/v1/admin/shoe-catalog")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminShoeCatalogEntryResponse createOrAppend(@Valid @RequestBody AdminShoeCatalogCreateRequest request) {
		String admin = accessGuard.requireAdmin();
		return service.createOrAppend(admin, request.manufacturer(), request.model(), request.version());
	}

	@PostMapping("/v1/admin/shoe-catalog/{id}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminShoeCatalogEntryResponse addVersion(@PathVariable String id, @Valid @RequestBody AdminShoeVersionCreateRequest request) {
		String admin = accessGuard.requireAdmin();
		return service.appendVersion(admin, id, request.version());
	}

	@DeleteMapping("/v1/admin/shoe-catalog/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		String admin = accessGuard.requireAdmin();
		service.delete(admin, id);
	}
}
