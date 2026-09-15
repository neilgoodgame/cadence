package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.uploads.Upload;
import com.cadence.api.uploads.UploadRepository;
import com.cadence.api.uploads.UploadService;
import com.cadence.api.uploads.UploadStatus;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * End-to-end: a real indoor-ride FIT file with CORE body-temperature sensor data, through the
 * actual upload pipeline, ends up with avg/max heat_strain/core_temp/skin_temp on the activity -
 * and, unlike avg_air_temp/avg_humidity (Stryd, running-only), these are populated for a bike
 * activity too. Mirrors the Python backend's
 * test_cycling_indoor_derives_avg_max_heat_strain_core_and_skin_temp_from_core_sensor.
 */
class HeatStrainIngestionTest extends IntegrationTest {

	@Autowired
	private UploadService uploadService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UploadRepository uploadRepository;
	@Autowired
	private ActivityRepository activityRepository;

	@Test
	void indoorRideWithCoreSensorDerivesAvgMaxHeatStrainCoreAndSkinTemp() throws Exception {
		User athlete = new User();
		athlete.setEmail("heat-strain-ingest@example.cc");
		athlete.setName("Heat Strain Athlete");
		athlete.setPassword("irrelevant-for-this-test");
		athlete = userRepository.save(athlete);

		byte[] fitBytes;
		try (InputStream in = getClass().getClassLoader()
				.getResourceAsStream("fit-fixtures/indoor_ride_zwift_core_sensor.fit")) {
			assertThat(in).isNotNull();
			fitBytes = in.readAllBytes();
		}
		MockMultipartFile file = new MockMultipartFile(
				"file", "indoor_ride_zwift_core_sensor.fit", "application/octet-stream", fitBytes);
		Upload upload = uploadService.createSingleUpload(athlete, file, null, null, null, null);

		Activity activity = awaitReadyActivity(upload.getId());
		assertThat(activity.getSport()).isEqualTo(Sport.BIKE);

		assertThat(activity.getAvgHeatStrain()).isNotNull();
		assertThat(activity.getMaxHeatStrain()).isNotNull();
		assertThat(activity.getAvgCoreTemp()).isNotNull();
		assertThat(activity.getMaxCoreTemp()).isNotNull();
		assertThat(activity.getAvgSkinTemp()).isNotNull();
		assertThat(activity.getMaxSkinTemp()).isNotNull();
		assertThat(activity.getMaxHeatStrain()).isGreaterThanOrEqualTo(activity.getAvgHeatStrain());
		assertThat(activity.getMaxCoreTemp()).isGreaterThanOrEqualTo(activity.getAvgCoreTemp());
		assertThat(activity.getMaxSkinTemp()).isGreaterThanOrEqualTo(activity.getAvgSkinTemp());
		// Bike rides never carry a Stryd footpod, so the run-only avg fields stay null.
		assertThat(activity.getAvgAirTemp()).isNull();
		assertThat(activity.getAvgHumidity()).isNull();
	}

	private Activity awaitReadyActivity(String uploadId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			Upload current = uploadRepository.findById(uploadId).orElseThrow();
			if (current.getStatus() == UploadStatus.READY) {
				return activityRepository.findById(current.getActivity().getId()).orElseThrow();
			}
			if (current.getStatus() != UploadStatus.QUEUED && current.getStatus() != UploadStatus.PROCESSING) {
				throw new AssertionError("Upload finished in unexpected status: " + current.getStatus());
			}
			Thread.sleep(200);
		}
		throw new AssertionError("Timed out waiting for upload to finish processing");
	}
}
