package com.cadence.api.admin;

import com.cadence.api.admin.dto.CatalogAuditLogEntryResponse;
import com.cadence.api.users.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditLogService {

	private final CatalogAuditLogEntryRepository repository;

	public AdminAuditLogService(CatalogAuditLogEntryRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void logAdded(String description, User by) {
		save(description, CatalogAuditAction.ADDED, by);
	}

	@Transactional
	public void logRemoved(String description, User by) {
		save(description, CatalogAuditAction.REMOVED, by);
	}

	private void save(String description, CatalogAuditAction action, User by) {
		CatalogAuditLogEntry entry = new CatalogAuditLogEntry();
		entry.setDescription(description);
		entry.setAction(action);
		entry.setBy(by);
		repository.save(entry);
	}

	@Transactional(readOnly = true)
	public List<CatalogAuditLogEntryResponse> list() {
		return repository.findAllWithByOrderByCreatedDesc().stream().map(this::toResponse).toList();
	}

	private CatalogAuditLogEntryResponse toResponse(CatalogAuditLogEntry entry) {
		String by = entry.getBy() != null ? entry.getBy().getName() : null;
		return new CatalogAuditLogEntryResponse(entry.getId(), entry.getDescription(), entry.getAction(), by, entry.getCreated());
	}
}
