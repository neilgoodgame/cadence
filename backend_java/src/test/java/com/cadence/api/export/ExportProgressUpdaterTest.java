package com.cadence.api.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExportProgressUpdaterTest extends IntegrationTest {

	@Autowired
	private ExportProgressUpdater exportProgressUpdater;
	@Autowired
	private ExportJobRepository exportJobRepository;
	@Autowired
	private UserRepository userRepository;

	@Test
	void updateStepPersistsImmediately() {
		User athlete = new User();
		athlete.setEmail("export-progress-updater@example.cc");
		athlete.setName("Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		athlete = userRepository.save(athlete);

		ExportJob job = new ExportJob();
		job.setAthlete(athlete);
		job = exportJobRepository.save(job);

		exportProgressUpdater.updateStep(job.getId(), "activities");

		// Re-fetched, not the in-memory `job` reference - proves the update actually committed
		// rather than just mutating a detached/managed instance in this test's own context.
		ExportJob reloaded = exportJobRepository.findById(job.getId()).orElseThrow();
		assertThat(reloaded.getCurrentStep()).isEqualTo("activities");
	}

	@Test
	void updateStepIsANoOpForAnUnknownJobId() {
		// Should not throw - the export job could have been deleted (e.g. a new export replacing
		// it, see ExportController.startExport) while an old async job's onStep callback is still
		// in flight.
		exportProgressUpdater.updateStep("exp_doesnotexist", "activities");
	}
}
