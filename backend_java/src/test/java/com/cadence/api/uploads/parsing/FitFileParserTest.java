package com.cadence.api.uploads.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

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
	void deviceComesFromFileIdManufacturerAndGarminProduct() throws IOException {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("fit-fixtures/running_outdoor_marathon.fit")) {
			assertThat(in).isNotNull();
			var parsed = FitFileParser.parse(in);

			// file_id: manufacturer garmin (1), garmin_product 3943 - the SDK's profile
			// resolves both to enum names, title-cased into a display string.
			assertThat(parsed.get(0).device()).isEqualTo("Garmin Epix Gen2");
		}
	}
}
