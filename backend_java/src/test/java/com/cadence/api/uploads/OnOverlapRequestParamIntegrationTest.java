package com.cadence.api.uploads;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the actual HTTP binding of the {@code on_overlap} multipart param on {@code
 * UploadController} - every other {@code on_overlap} test in this module (and the equivalent
 * ones in {@code OverlapOnImportIntegrationTest}/{@code OverlapOnSingleUploadIntegrationTest})
 * calls {@code UploadService} directly, bypassing the controller entirely, so none of them would
 * have caught that Spring's default enum {@code Converter} is case-sensitive by constant name
 * ("MARK", not the wire value "mark") and needs an explicit entry in {@code WebConfig}, exactly
 * like {@code OnDuplicate} already has - missing that entry 500'd every real request carrying
 * {@code on_overlap} on both this and the batch endpoint.
 */
@AutoConfigureMockMvc
class OnOverlapRequestParamIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccessTokenService accessTokenService;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private String bearer(User user) {
		return "Bearer " + accessTokenService
				.create(user, new CreateAccessTokenRequest("on-overlap-test", List.of("activities:write"), null, null), Set.of())
				.secret();
	}

	@Test
	void onOverlapAsAMultipartParamBindsOnTheSingleUploadEndpoint() throws Exception {
		User athlete = newAthlete("on-overlap-param-single@example.cc");
		MockMultipartFile file = new MockMultipartFile("file", "a.fit", "application/octet-stream", new byte[] { 1, 2, 3 });

		mockMvc.perform(multipart("/v1/activities").file(file).param("on_overlap", "mark")
						.header("Authorization", bearer(athlete)))
				.andExpect(status().isAccepted());
	}

	@Test
	void onOverlapAsAMultipartParamBindsOnTheBatchUploadEndpoint() throws Exception {
		User athlete = newAthlete("on-overlap-param-batch@example.cc");
		java.io.ByteArrayOutputStream zipBytes = new java.io.ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("a.fit"));
			zip.write(new byte[] { 1, 2, 3 });
			zip.closeEntry();
		}
		MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", zipBytes.toByteArray());

		mockMvc.perform(multipart("/v1/activities/batch").file(file).param("on_overlap", "mark")
						.header("Authorization", bearer(athlete)))
				.andExpect(status().isAccepted());
	}
}
