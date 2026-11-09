package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden-fixture parity with the Python backend's athletes/threshold_decay.py:
 * threshold-decay-fixtures/ is a copy of backend/athletes/fixtures/decay/, written by its
 * regenerate.py. A failure here after a deliberate change to either implementation means porting
 * the change to the other and regenerating - never editing expected.json by hand.
 */
class ThresholdDecayParityTest {

	private static final Path FIXTURES = Path.of("src/test/resources/threshold-decay-fixtures");
	private static final Path PYTHON_FIXTURES = Path.of("../backend/athletes/fixtures/decay");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static LocalDate date(JsonNode node) {
		return node == null || node.isNull() ? null : LocalDate.parse(node.asText());
	}

	private static String text(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}

	private static ThresholdDecay.DecaySettings settings(JsonNode s) {
		return new ThresholdDecay.DecaySettings(
				s.get("window_days").asInt(), s.get("sanity_pct").asInt(), s.get("tau_days").asDouble(),
				s.get("min_pct").asDouble(), s.get("max_pct").asDouble(), s.get("enabled").asBoolean());
	}

	private static List<ThresholdDecay.DecayCandidate> candidates(JsonNode arr) {
		List<ThresholdDecay.DecayCandidate> out = new ArrayList<>();
		for (JsonNode c : arr) {
			out.add(new ThresholdDecay.DecayCandidate(
					date(c.get("date")), c.get("implied_value").asDouble(), text(c.get("activity_id")), c.get("accepted").asBoolean()));
		}
		return out;
	}

	private static List<ThresholdDecay.ManualEntry> manualEntries(JsonNode arr) {
		List<ThresholdDecay.ManualEntry> out = new ArrayList<>();
		for (JsonNode m : arr) {
			out.add(new ThresholdDecay.ManualEntry(date(m.get("date")), m.get("value").asDouble(), text(m.get("activity_id"))));
		}
		return out;
	}

	private static Map<LocalDate, Double> loadByDay(JsonNode obj) {
		Map<LocalDate, Double> out = new HashMap<>();
		Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			out.put(LocalDate.parse(entry.getKey()), entry.getValue().asDouble());
		}
		return out;
	}

	@Test
	void everyGoldenCaseMatchesThePythonBackendExactly() throws IOException {
		JsonNode cases = MAPPER.readTree(FIXTURES.resolve("expected.json").toFile()).get("cases");
		assertThat(cases.size()).isGreaterThanOrEqualTo(10);
		for (JsonNode c : cases) {
			String name = c.get("name").asText();
			ThresholdField field = ThresholdField.fromWireValue(c.get("field").asText());
			ThresholdDecay.DecaySettings settings = settings(c.get("settings"));
			JsonNode seedValueNode = c.get("seed_anchor_value");
			Double seedAnchorValue = seedValueNode == null || seedValueNode.isNull() ? null : seedValueNode.asDouble();
			LocalDate seedAnchorDate = date(c.get("seed_anchor_date"));
			String seedAnchorKind = text(c.get("seed_anchor_kind"));

			List<ThresholdDecay.Step> steps = ThresholdDecay.simulate(
					field, settings, seedAnchorValue, seedAnchorDate, seedAnchorKind, candidates(c.get("candidates")),
					manualEntries(c.get("manual_entries")), loadByDay(c.get("load_by_day")), date(c.get("start")), date(c.get("end")));

			JsonNode expected = c.get("steps");
			assertThat(steps).as(name).hasSize(expected.size());
			for (int i = 0; i < expected.size(); i++) {
				JsonNode e = expected.get(i);
				ThresholdDecay.Step s = steps.get(i);
				String at = name + " step " + i;
				assertThat(s.value()).as(at).isEqualTo(e.get("value").asDouble());
				assertThat(s.kind()).as(at).isEqualTo(e.get("kind").asText());
				assertThat(s.activityId()).as(at).isEqualTo(text(e.get("activity_id")));
				assertThat(s.effectiveFrom()).as(at).isEqualTo(date(e.get("effective_from")));
				assertThat(s.currentFrom()).as(at).isEqualTo(date(e.get("current_from")));
			}
		}
	}

	@Test
	void fixtureIsAnExactCopyOfThePythonBackends() throws IOException {
		if (!Files.isDirectory(PYTHON_FIXTURES)) return; // backend/ not checked out alongside
		Path python = PYTHON_FIXTURES.resolve("expected.json");
		assertThat(Files.readAllBytes(FIXTURES.resolve("expected.json"))).isEqualTo(Files.readAllBytes(python));
	}
}
