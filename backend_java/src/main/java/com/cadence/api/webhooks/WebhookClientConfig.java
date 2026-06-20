package com.cadence.api.webhooks;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookClientConfig {

	private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	public RestClient webhookRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(DELIVERY_TIMEOUT);
		requestFactory.setReadTimeout(DELIVERY_TIMEOUT);
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
