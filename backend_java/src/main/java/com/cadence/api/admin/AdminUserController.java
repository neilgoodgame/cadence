package com.cadence.api.admin;

import com.cadence.api.admin.dto.AdminUserResponse;
import com.cadence.api.admin.dto.AdminUserUpdateRequest;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminUserController {

	private final AdminUserService service;
	private final AccessGuard accessGuard;

	public AdminUserController(AdminUserService service, AccessGuard accessGuard) {
		this.service = service;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/admin/users")
	public DataListResponse<AdminUserResponse> list(@RequestParam(required = false) String q) {
		accessGuard.requireAdmin();
		return new DataListResponse<>(service.list(q));
	}

	@PatchMapping("/v1/admin/users/{id}")
	public AdminUserResponse update(@PathVariable String id, @Valid @RequestBody AdminUserUpdateRequest request) {
		accessGuard.requireAdmin();
		return service.update(id, request);
	}
}
