package com.cadence.api.admin;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CatalogAuditLogEntryRepository extends JpaRepository<CatalogAuditLogEntry, String> {

	// "join fetch" since the response mapping reads by.getName() after the loading
	// transaction has closed - same reasoning as every other "with users" query in this app.
	@Query("select e from CatalogAuditLogEntry e left join fetch e.by order by e.created desc")
	List<CatalogAuditLogEntry> findAllWithByOrderByCreatedDesc();
}
