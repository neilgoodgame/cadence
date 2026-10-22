package com.cadence.api.garmin;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import com.cadence.api.common.error.UnprocessableEntityException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Garmin Connect Personal API: exchange a Personal API key for a 24-hour bearer token, list
 * activities, download an activity's ZIP (which holds the original .fit). Paths come from
 * Garmin's own connect-personal-api-python client. Neither the key nor the token is ever
 * logged or put in an error message.
 */
@Component
public class GarminApiClient {

	/** Garmin's list endpoint 500s above 50 per page. */
	static final int PAGE_SIZE = 50;
	/** Safety ceiling on how many summaries one sync will page through. */
	static final int MAX_SUMMARIES = 1000;

	private static final DateTimeFormatter GMT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public record ActivitySummary(long activityId, String startTimeGMT, String activityName) {

		public Instant startTime() {
			return LocalDateTime.parse(startTimeGMT, GMT_FORMAT).toInstant(ZoneOffset.UTC);
		}
	}

	private final RestClient restClient;

	public GarminApiClient(CadenceProperties properties) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(60));
		this.restClient = RestClient.builder()
				.baseUrl(properties.garmin().apiBaseUrl())
				.requestFactory(factory)
				.build();
	}

	public String exchangeKey(String apiKey) {
		String token = call(() -> restClient.post()
				.uri("/personalapitoken-service/personal-api/personal-api-key/token")
				.header("Authorization", "ApiKey " + apiKey)
				.retrieve()
				.body(String.class));
		if (token == null || token.isBlank()) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Garmin returned no access token.");
		}
		return token.trim();
	}

	/** Newest first, matching Garmin's own order. */
	public List<ActivitySummary> listSince(String token, LocalDate startDate) {
		List<ActivitySummary> all = new ArrayList<>();
		for (int offset = 0; offset < MAX_SUMMARIES; offset += PAGE_SIZE) {
			int start = offset;
			ActivitySummary[] page = call(() -> restClient.get()
					.uri("/activitylist-service/activities/search/activities?start={s}&limit={l}&startDate={d}",
							start, PAGE_SIZE, startDate.toString())
					.header("Authorization", "Bearer " + token)
					.retrieve()
					.body(ActivitySummary[].class));
			if (page == null || page.length == 0) {
				break;
			}
			all.addAll(List.of(page));
			if (page.length < PAGE_SIZE) {
				break;
			}
		}
		return all;
	}

	/** Garmin's single newest activity, or empty when the account has none. */
	public List<ActivitySummary> latest(String token) {
		ActivitySummary[] page = call(() -> restClient.get()
				.uri("/activitylist-service/activities/search/activities?start=0&limit=1")
				.header("Authorization", "Bearer " + token)
				.retrieve()
				.body(ActivitySummary[].class));
		return page == null || page.length == 0 ? List.of() : List.of(page[0]);
	}

	public byte[] downloadZip(String token, long activityId) {
		byte[] body = call(() -> restClient.get()
				.uri("/download-service/files/activity/{id}", activityId)
				.header("Authorization", "Bearer " + token)
				.retrieve()
				.body(byte[].class));
		if (body == null || body.length == 0) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Garmin returned an empty file.");
		}
		return body;
	}

	private static <T> T call(Supplier<T> request) {
		try {
			return request.get();
		}
		catch (RestClientResponseException e) {
			int status = e.getStatusCode().value();
			if (status == 401 || status == 403) {
				throw new UnprocessableEntityException(
						"Garmin rejected your API key - it may have expired or been revoked.", "api_key");
			}
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Garmin returned an error (HTTP " + status + ").");
		}
		catch (RestClientException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Garmin.");
		}
	}
}
