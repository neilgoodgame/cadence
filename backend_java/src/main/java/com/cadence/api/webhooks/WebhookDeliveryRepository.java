package com.cadence.api.webhooks;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

	// deliver() reads webhook.url/secret after the loading transaction may have closed (it runs
	// on a separate @Async thread), so the association needs to come back already initialized.
	@Query("select d from WebhookDelivery d join fetch d.webhook where d.id = :id")
	Optional<WebhookDelivery> findByIdWithWebhook(@Param("id") Long id);
}
