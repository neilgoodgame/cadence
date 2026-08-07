package com.cadence.api.admin;

import com.cadence.api.admin.dto.CatalogAuditLogEntryResponse;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAuditLogController {

	private final AdminAuditLogService service;
	private final AccessGuard accessGuard;

	public AdminAuditLogController(AdminAuditLogService service, AccessGuard accessGuard) {
		this.service = service;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/v1/admin/audit-log")
	public DataListResponse<CatalogAuditLogEntryResponse> list() {
		accessGuard.requireAdmin();
		return new DataListResponse<>(service.list());
	}
}
