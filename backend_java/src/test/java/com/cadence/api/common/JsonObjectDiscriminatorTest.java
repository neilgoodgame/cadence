package com.cadence.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.dto.StreamsResponse;
import com.cadence.api.common.paging.CursorPage;
import com.cadence.api.uploads.UploadBatchStatus;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.uploads.dto.UploadBatchCounts;
import com.cadence.api.uploads.dto.UploadBatchResponse;
import com.cadence.api.uploads.dto.UploadResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * UploadResponse, UploadBatchResponse, CursorPage, and StreamsResponse each had a bare
 * {@code object()} accessor meant to add the contract's {@code "object"} discriminator -
 * Jackson's default record handling only serializes canonical components, so a plain method
 * (not following getX()/isX() naming, and unannotated) is silently dropped rather than
 * erroring, which is how this went unnoticed. Covering all four call sites here since they're
 * the same root cause, not separate bugs.
 */
class JsonObjectDiscriminatorTest {

	private final ObjectMapper mapper =
			new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

	@Test
	void uploadResponseIncludesObjectDiscriminator() throws Exception {
		var response = new UploadResponse(
				"upl_1", UploadStatus.READY, 1.0, "f.fit", "act_1", null, null, Instant.now(), Instant.now());
		Map<String, Object> json = mapper.convertValue(response, Map.class);
		assertThat(json).containsEntry("object", "upload");
	}

	@Test
	void uploadBatchResponseIncludesObjectDiscriminator() throws Exception {
		var response = new UploadBatchResponse(
				"bat_1", UploadBatchStatus.COMPLETED, "f.zip", 1.0, new UploadBatchCounts(1, 1, 0, 0, 0),
				List.of(), Instant.now(), Instant.now());
		Map<String, Object> json = mapper.convertValue(response, Map.class);
		assertThat(json).containsEntry("object", "upload_batch");
	}

	@Test
	void cursorPageIncludesObjectDiscriminator() throws Exception {
		var page = new CursorPage<>(false, null, List.of("a"));
		Map<String, Object> json = mapper.convertValue(page, Map.class);
		assertThat(json).containsEntry("object", "list");
	}

	@Test
	void streamsResponseIncludesObjectDiscriminator() throws Exception {
		var response = new StreamsResponse("high", Map.of("t", List.of(1, 2, 3)));
		Map<String, Object> json = mapper.convertValue(response, Map.class);
		assertThat(json).containsEntry("object", "streams");
	}
}
