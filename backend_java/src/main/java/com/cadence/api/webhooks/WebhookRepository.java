package com.cadence.api.webhooks;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookRepository extends JpaRepository<Webhook, String> {

	List<Webhook> findByOwnerIdOrderByCreatedDesc(String ownerId);

	Optional<Webhook> findByIdAndOwnerId(String id, String ownerId);

	@Query(value = "select * from webhook where status = 'active' and jsonb_exists(events, :event)", nativeQuery = true)
	List<Webhook> findActiveSubscribersTo(@Param("event") String event);
}
