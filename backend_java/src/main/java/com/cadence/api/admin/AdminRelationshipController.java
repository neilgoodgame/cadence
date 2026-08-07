package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminRelationshipResponse;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminRelationshipController {

	private final AdminRelationshipService service;
	private final AccessGuard accessGuard;

	public AdminRelationshipController(AdminRelationshipService service, AccessGuard accessGuard) {
		this.service = service;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/admin/relationships")
	public DataListResponse<AdminRelationshipResponse> list() {
		accessGuard.requireAdmin();
		return new DataListResponse<>(service.list());
	}

	@DeleteMapping("/v1/admin/relationships/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String id) {
		accessGuard.requireAdmin();
		service.revoke(id);
	}
}
