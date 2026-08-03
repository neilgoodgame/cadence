package com.cadence.api.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ConflictException;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.export.dto.ExportJobResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ExportControllerIntegrationTest extends IntegrationTest {

	@Autowired
	private ExportController exportController;

	@Autowired
	private ExportJobRepository exportJobRepository;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Export Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAs(String userId) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	private ExportJob newJob(User athlete, ExportStatus status) {
		ExportJob job = new ExportJob();
		job.setAthlete(athlete);
		job.setStatus(status);
		return exportJobRepository.save(job);
	}

	@Test
	void startExportReturnsAcceptedWithLocationAndRetryAfter() {
		User athlete = newAthlete("export-start@example.cc");
		authAs(athlete.getId());

		ResponseEntity<ExportJobResponse> response = exportController.startExport();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/v1/export/" + response.getBody().id());
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
		assertThat(exportJobRepository.findByAthleteId(athlete.getId())).isPresent();
	}

	@Test
	void startExportReplacesAnyPreviousJobForTheSameAthlete() {
		User athlete = newAthlete("export-replace@example.cc");
		ExportJob previous = newJob(athlete, ExportStatus.READY);
		authAs(athlete.getId());

		ResponseEntity<ExportJobResponse> response = exportController.startExport();

		assertThat(response.getBody().id()).isNotEqualTo(previous.getId());
		assertThat(exportJobRepository.findById(previous.getId())).isEmpty();
	}

	@Test
	void getExportReturns403ForNonOwner() {
		User athlete = newAthlete("export-owner@example.cc");
		User other = newAthlete("export-other@example.cc");
		ExportJob job = newJob(athlete, ExportStatus.READY);
		authAs(other.getId());

		assertThatThrownBy(() -> exportController.getExport(job.getId())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void getExportReturns404ForUnknownId() {
		authAs(newAthlete("export-unknown@example.cc").getId());

		assertThatThrownBy(() -> exportController.getExport("exp_doesnotexist")).isInstanceOf(NotFoundException.class);
	}

	@Test
	void getExportIncludesRetryAfterOnlyWhileQueuedOrProcessing() {
		// One export per athlete (unique index on athlete_id) - two athletes here, one per status.
		ExportJob queued = newJob(newAthlete("export-inflight-queued@example.cc"), ExportStatus.QUEUED);
		ExportJob ready = newJob(newAthlete("export-inflight-ready@example.cc"), ExportStatus.READY);

		authAs(queued.getAthlete().getId());
		assertThat(exportController.getExport(queued.getId()).getHeaders().getFirst("Retry-After")).isEqualTo("5");

		authAs(ready.getAthlete().getId());
		assertThat(exportController.getExport(ready.getId()).getHeaders().getFirst("Retry-After")).isNull();
	}

	@Test
	void downloadReturns409WhenExportIsNotReady() {
		User athlete = newAthlete("export-notready@example.cc");
		ExportJob job = newJob(athlete, ExportStatus.PROCESSING);
		authAs(athlete.getId());

		assertThatThrownBy(() -> exportController.download(job.getId())).isInstanceOf(ConflictException.class);
	}

	@Test
	void downloadReturns403ForNonOwner() {
		User athlete = newAthlete("export-dl-owner@example.cc");
		User other = newAthlete("export-dl-other@example.cc");
		ExportJob job = newJob(athlete, ExportStatus.READY);
		authAs(other.getId());

		assertThatThrownBy(() -> exportController.download(job.getId())).isInstanceOf(ForbiddenException.class);
	}
}
