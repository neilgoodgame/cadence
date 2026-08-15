package com.cadence.api.activities;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently creates any missing `record` partitions for the next 6 months, so new
 * activity data always has a real (non-DEFAULT) partition to land in well ahead of time.
 * Same monthly boundaries/naming as V11__record_partitions.sql's initial partition
 * scheme - this just keeps rolling the forward edge ahead over time. Safe to run
 * repeatedly (CREATE TABLE IF NOT EXISTS) and safe to run late (that migration's
 * DEFAULT partition catches anything this hasn't created yet in the meantime).
 */
@Service
public class PartitionMaintenanceService {

	private final EntityManager entityManager;

	public PartitionMaintenanceService(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Scheduled(cron = "0 0 0 1 * *") // 00:00 on the 1st of every month
	@Transactional
	public void ensureRecordPartitions() {
		LocalDate today = LocalDate.now();
		for (int offset = 0; offset <= 6; offset++) { // this month through 6 months ahead
			LocalDate monthStart = today.withDayOfMonth(1).plusMonths(offset);
			LocalDate nextMonthStart = monthStart.plusMonths(1);
			String name = "record_p%04d%02d".formatted(monthStart.getYear(), monthStart.getMonthValue());
			entityManager
					.createNativeQuery("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF record "
							+ "FOR VALUES FROM ('" + monthStart + " 00:00:00+00') TO ('" + nextMonthStart + " 00:00:00+00')")
					.executeUpdate();
		}
	}
}
