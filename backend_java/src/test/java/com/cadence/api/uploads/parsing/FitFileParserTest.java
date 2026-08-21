package com.cadence.api.uploads.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.garmin.fit.DateTime;
import com.garmin.fit.File;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.Manufacturer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-world FIT fixture (Stryd-equipped run), not a synthetic one - the Garmin FIT SDK
 * decodes from a real binary stream, not a list of pre-built messages, so there's no
 * lightweight way to mock individual FIT messages the way the Python backend's
 * fitparse-based tests do.
 */
class FitFileParserTest {

	@Test
	void lapsGetAvgPowerFromStrydSamplesWhenLapMessageOmitsIt() throws IOException {
		// This device (Stryd-equipped run) never fills in the lap message's own avg_power
		// field - confirmed directly against the fixture's raw lap messages, all null - so
		// every one of these must come from the sample-average fallback, not the lap
		// message itself.
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			var parsed = FitFileParser.parse(in);

			assertThat(parsed).hasSize(1);
			ParsedActivity result = parsed.get(0);

			assertThat(result.laps()).hasSize(5);
			assertThat(result.laps()).allSatisfy(lap -> assertThat(lap.avgPower()).isNotNull());
		}
	}

	@Test
	void fitWithNoRecordMessagesThrowsNoActivityData(@TempDir Path tempDir) throws IOException {
		// Garmin account exports mix tiny metadata-stub FITs (file_id/device info only, no
		// record messages) in with real activities; the parser must flag these distinctly so
		// batch imports can skip rather than fail them. The SDK's encoder builds one here.
		Path stub = tempDir.resolve("stub.fit");
		FileEncoder encoder = new FileEncoder(stub.toFile(), Fit.ProtocolVersion.V2_0);
		FileIdMesg fileId = new FileIdMesg();
		fileId.setType(File.DEVICE);
		fileId.setManufacturer(Manufacturer.GARMIN);
		fileId.setTimeCreated(new DateTime(0));
		encoder.write(fileId);
		encoder.close();

		try (InputStream in = new ByteArrayInputStream(Files.readAllBytes(stub))) {
			assertThatThrownBy(() -> FitFileParser.parse(in))
					.isInstanceOf(NoActivityDataException.class)
					.hasMessageContaining("No record messages");
		}
	}

	@Test
	void deviceComesFromFileIdManufacturerAndGarminProduct() throws IOException {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			var parsed = FitFileParser.parse(in);

			// file_id: manufacturer garmin (1), garmin_product 3943 - the SDK's profile
			// resolves both to enum names, title-cased into a display string.
			assertThat(parsed.get(0).device()).isEqualTo("Garmin Epix Gen2");
		}
	}

	@Test
	void coreTemperatureComesFromTheNativeFitFieldWhenNoDeveloperFieldIsPresent() throws IOException {
		// Zwift's export: core_temperature isn't a developer field here at all (confirmed
		// directly against the fixture's field_description messages - only skin_temperature,
		// core_data_quality, core_reserved, and heat_strain_index are declared) - the CORE
		// sensor's actual core-temp reading instead comes through Garmin's later-added native
		// FIT field, record.core_temperature (RecordMesg#getCoreTemperature).
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/indoor_ride_zwift_core_sensor.fit")) {
			assertThat(in).isNotNull();
			var parsed = FitFileParser.parse(in);

			ParsedActivity result = parsed.get(0);
			assertThat(result.samples().get(0).coreTemp()).isEqualTo(37.0);
			assertThat(result.samples()).allSatisfy(s -> {
				if (s.coreTemp() != null) {
					assertThat(s.coreTemp()).isBetween(37.0, 38.64);
				}
			});
			assertThat(result.samples()).anySatisfy(s -> assertThat(s.coreTemp()).isNotNull());
		}
	}

	@Test
	void leftBalancePctConvertsRightFlaggedValueToLeftPercent() {
		// 0x80 (right flag) | 62 (62% right) -> 38% left.
		assertThat(FitFileParser.leftBalancePct((short) (0x80 | 62))).isEqualTo(38.0);
	}

	@Test
	void leftBalancePctWithoutRightFlagIsAlreadyLeftPercent() {
		assertThat(FitFileParser.leftBalancePct((short) 45)).isEqualTo(45.0);
	}

	@Test
	void leftBalancePctNullWhenFieldMissing() {
		assertThat(FitFileParser.leftBalancePct(null)).isNull();
	}
}
