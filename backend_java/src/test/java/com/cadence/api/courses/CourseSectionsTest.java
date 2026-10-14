package com.cadence.api.courses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Golden-fixture parity with the Python backend's courses/sections.py: section-fixtures/ is a
 * copy of backend/courses/fixtures/sections/, written by its regenerate.py. A failure here after a
 * deliberate change to either implementation means porting the change to the other and
 * regenerating - never editing expected.json by hand.
 */
class CourseSectionsTest {

	private static final Path FIXTURES = Path.of("src/test/resources/section-fixtures");
	private static final Path PYTHON_FIXTURES = Path.of("../backend/courses/fixtures/sections");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private record Profile(List<Double> distancesKm, List<Double> elevationsM) {}

	private static Profile profile(String name) throws IOException {
		JsonNode root = MAPPER.readTree(FIXTURES.resolve(name + ".json").toFile());
		List<Double> distances = new ArrayList<>();
		List<Double> elevations = new ArrayList<>();
		root.get("distance_km").forEach(n -> distances.add(n.asDouble()));
		root.get("elevation_m").forEach(n -> elevations.add(n.asDouble()));
		return new Profile(distances, elevations);
	}

	private static CourseSections.Settings settings(JsonNode s) {
		return new CourseSections.Settings(
				s.get("algorithm").asText(), s.get("flat_max_pct").asDouble(), s.get("steep_min_pct").asDouble(),
				s.get("fit_tolerance_m").asDouble(), s.get("min_section_m").asDouble(), s.get("keep_min_rise_m").asDouble(),
				s.get("spike_window_m").asDouble(), s.get("smoothing_window_m").asDouble(), s.get("resample_step_m").asDouble());
	}

	@Test
	void everyGoldenCaseMatchesThePythonBackendExactly() throws IOException {
		JsonNode cases = MAPPER.readTree(FIXTURES.resolve("expected.json").toFile()).get("cases");
		assertThat(cases.size()).isGreaterThanOrEqualTo(10);
		for (JsonNode c : cases) {
			String where = c.get("profile").asText() + " " + c.get("settings");
			Profile p = profile(c.get("profile").asText());
			CourseSections.Result result = CourseSections.detect(p.distancesKm(), p.elevationsM(), settings(c.get("settings")));
			assertThat(result.elevationQuality()).as(where).isEqualTo(c.get("elevation_quality").asText());
			JsonNode expected = c.get("sections");
			assertThat(result.sections()).as(where).hasSize(expected.size());
			for (int k = 0; k < expected.size(); k++) {
				JsonNode e = expected.get(k);
				CourseSections.Section s = result.sections().get(k);
				String at = where + " section " + k;
				assertThat(s.type()).as(at).isEqualTo(e.get("type").asText());
				assertThat(s.startKm()).as(at).isEqualTo(e.get("start_km").asDouble());
				assertThat(s.endKm()).as(at).isEqualTo(e.get("end_km").asDouble());
				assertThat(s.lengthM()).as(at).isEqualTo(e.get("length_m").asInt());
				assertThat(s.gainM()).as(at).isEqualTo(e.get("gain_m").asInt());
				assertThat(s.lossM()).as(at).isEqualTo(e.get("loss_m").asInt());
				assertThat(s.avgGradePct()).as(at).isEqualTo(e.get("avg_grade_pct").asDouble());
				assertThat(s.steepest100mPct()).as(at).isEqualTo(e.get("steepest_100m_pct").asDouble());
			}
		}
	}

	@Test
	void fixturesAreAnExactCopyOfThePythonBackends() throws IOException {
		if (!Files.isDirectory(PYTHON_FIXTURES)) return; // backend/ not checked out alongside
		try (Stream<Path> files = Files.list(PYTHON_FIXTURES)) {
			for (Path py : files.filter(f -> f.toString().endsWith(".json")).toList()) {
				assertThat(Files.readAllBytes(FIXTURES.resolve(py.getFileName())))
						.as(py.getFileName().toString()).isEqualTo(Files.readAllBytes(py));
			}
		}
	}

	@Test
	void mountRooseveltIsAClimbWithBothAlgorithms() throws IOException {
		Profile p = profile("chicago");
		for (String algorithm : List.of(CourseSections.ALGORITHM_DOUGLAS_PEUCKER, CourseSections.ALGORITHM_BOTTOM_UP)) {
			List<CourseSections.Section> sections =
					CourseSections.detect(p.distancesKm(), p.elevationsM(), CourseSections.Settings.DEFAULTS.withAlgorithm(algorithm)).sections();
			assertThat(sections).as(algorithm)
					.anyMatch(s -> s.startKm() >= 42.2 && CourseSections.CLIMB.equals(s.type()));
		}
	}

	@Test
	void steepestFieldSerializesWithTheContractName() throws IOException {
		ObjectMapper snake = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		String json = snake.writeValueAsString(new CourseSections.Section("climb", 1.0, 2.0, 1000, 20, 0, 2.0, 3.1));
		assertThat(json).contains("\"steepest_100m_pct\":3.1").contains("\"avg_grade_pct\":2.0").contains("\"start_km\":1.0");
	}

	@Test
	void noElevationIsMissing() {
		List<Double> distances = new ArrayList<>();
		for (int i = 0; i < 500; i++) distances.add(i * 0.01);
		CourseSections.Result nulls = CourseSections.detect(distances, Collections.nCopies(500, null), CourseSections.Settings.DEFAULTS);
		assertThat(nulls.elevationQuality()).isEqualTo(CourseSections.ELEVATION_QUALITY_MISSING);
		assertThat(nulls.sections()).isEmpty();
		CourseSections.Result zeros = CourseSections.detect(distances, Collections.nCopies(500, 0.0), CourseSections.Settings.DEFAULTS);
		assertThat(zeros.elevationQuality()).isEqualTo(CourseSections.ELEVATION_QUALITY_MISSING);
	}

	@Test
	void pointsFarApartAreSparse() {
		List<Double> distances = new ArrayList<>();
		List<Double> elevations = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			distances.add(i * 0.25);
			elevations.add(100.0 + (i > 10 && i < 20 ? (i - 10) * 7.5 : 0.0));
		}
		CourseSections.Result result = CourseSections.detect(distances, elevations, CourseSections.Settings.DEFAULTS);
		assertThat(result.elevationQuality()).isEqualTo(CourseSections.ELEVATION_QUALITY_SPARSE);
		assertThat(result.sections()).isEmpty();
	}

	@Test
	void outOfRangeSettingsAreRejected() {
		CourseSections.Settings d = CourseSections.Settings.DEFAULTS;
		assertThatThrownBy(() -> d.withAlgorithm("kmeans").validate()).hasMessageContaining("algorithm");
		assertThatThrownBy(() -> new CourseSections.Settings(d.algorithm(), d.flatMaxPct(), d.steepMinPct(), 0.1,
				d.minSectionM(), d.keepMinRiseM(), d.spikeWindowM(), d.smoothingWindowM(), d.resampleStepM()).validate())
				.hasMessageContaining("fit_tolerance_m");
		assertThatThrownBy(() -> new CourseSections.Settings(d.algorithm(), 3.0, 2.5, d.fitToleranceM(),
				d.minSectionM(), d.keepMinRiseM(), d.spikeWindowM(), d.smoothingWindowM(), d.resampleStepM()).validate())
				.hasMessageContaining("steep_min_pct");
	}
}
