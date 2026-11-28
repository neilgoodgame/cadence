package com.cadence.api.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.athletes.ExcludedThresholdCandidate;
import com.cadence.api.athletes.ExcludedThresholdCandidateRepository;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.export.ExportWriter;
import com.cadence.api.imports.dto.ImportCounts;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Issue #616: an exclusion must survive export/import, or the next ledger rebuild on the
 * importing account would bring the excluded (known-bad) anchor straight back. Mirrors the
 * Python backend's dataexport/tests.py::ThresholdExclusionExportImportTests. */
class ThresholdExclusionExportImportIntegrationTest extends IntegrationTest {

	@Autowired
	private ExportWriter exportWriter;
	@Autowired
	private ImportReader importReader;
	@Autowired
	private JsonMapper jsonMapper;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private ExcludedThresholdCandidateRepository excludedRepository;

	private record Source(User athlete, Activity ride, Activity run) {
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Exclusion Export " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity activity(User owner, Sport sport, String name, String start) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(sport);
		activity.setName(name);
		activity.setStartDate(Instant.parse(start));
		activity.setMovingTime(1800);
		activity.setDistanceKm(10.0);
		return activityRepository.save(activity);
	}

	private void exclude(User athlete, ThresholdField field, Activity activity, User setBy) {
		ExcludedThresholdCandidate row = new ExcludedThresholdCandidate();
		row.setAthlete(athlete);
		row.setField(field);
		row.setActivity(activity);
		row.setSetBy(setBy);
		excludedRepository.save(row);
	}

	private Source source(String email) {
		User athlete = newUser(email);
		Activity ride = activity(athlete, Sport.BIKE, "Bad power ride", "2026-01-01T07:00:00Z");
		Activity run = activity(athlete, Sport.RUN, "Bad pace run", "2026-01-02T07:00:00Z");
		exclude(athlete, ThresholdField.FTP, ride, athlete);
		exclude(athlete, ThresholdField.THRESHOLD_PACE, run, null);
		return new Source(athlete, ride, run);
	}

	private Path export(User athlete, Sport sport) throws IOException {
		Path file = Files.createTempFile("exclusion-export-test", ".json.gz");
		try (JsonGenerator generator = jsonMapper.createGenerator(
				new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
			exportWriter.write(athlete.getId(), sport, generator);
		}
		return file;
	}

	private JsonNode section(Path file) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
			return jsonMapper.readTree(in).get("threshold_exclusions");
		}
	}

	private List<ExcludedThresholdCandidate> exclusionsOf(User athlete) {
		return excludedRepository.findByAthleteIdOrderByFieldAscActivityIdAsc(athlete.getId());
	}

	@Test
	void exclusionsRoundTripOntoTheNewActivities() throws IOException {
		Source s = source("exclusion-export-roundtrip-source@example.cc");
		User target = newUser("exclusion-export-roundtrip-target@example.cc");
		Path file = export(s.athlete(), null);
		try {
			assertThat(jsonMapper.convertValue(section(file), List.class)).containsExactly(
					Map.of("field", "ftp", "activity_id", s.ride().getId()),
					Map.of("field", "threshold_pace", "activity_id", s.run().getId()));

			importReader.read(target.getId(), file);

			assertThat(exclusionsOf(target)).extracting(
					ExcludedThresholdCandidate::getField,
					e -> activityRepository.findById(e.getActivity().getId()).orElseThrow().getName(),
					e -> activityRepository.findById(e.getActivity().getId()).orElseThrow().getAthlete().getId(),
					e -> e.getSetBy() == null)
					.containsExactlyInAnyOrder(
							tuple(ThresholdField.FTP, "Bad power ride", target.getId(), true),
							tuple(ThresholdField.THRESHOLD_PACE, "Bad pace run", target.getId(), true));
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void aSportFilteredExportCarriesOnlyThatSportsExclusions() throws IOException {
		Source s = source("exclusion-export-sport-filter@example.cc");
		Path file = export(s.athlete(), Sport.BIKE);
		try {
			assertThat(jsonMapper.convertValue(section(file), List.class))
					.containsExactly(Map.of("field", "ftp", "activity_id", s.ride().getId()));
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void reimportingTheSameAthletesOwnExportDoesNotDuplicateOrSkip() throws IOException {
		Source s = source("exclusion-export-reimport@example.cc");
		Path file = export(s.athlete(), null);
		try {
			ImportCounts counts = importReader.read(s.athlete().getId(), file);

			// The two activities dedup onto the existing ones (skipped); the exclusions on them
			// already exist, which insertIfAbsent leaves alone without counting a skip.
			assertThat(counts.itemsSkipped()).isEqualTo(2);
			assertThat(exclusionsOf(s.athlete())).hasSize(2);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void anUnmappedActivityOrUnknownFieldIsSkipped() throws IOException {
		User target = newUser("exclusion-export-dangling@example.cc");
		Activity ride = activity(target, Sport.BIKE, "Unrelated ride", "2026-01-03T07:00:00Z");
		Path file = Files.createTempFile("exclusion-dangling-test", ".json.gz");
		try {
			try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
				out.write(("{\"activities\":[],\"threshold_exclusions\":["
						+ "{\"field\":\"ftp\",\"activity_id\":\"act_doesnotexist\"},"
						+ "{\"field\":\"weight\",\"activity_id\":\"" + ride.getId() + "\"}]}")
						.getBytes(StandardCharsets.UTF_8));
			}

			ImportCounts counts = importReader.read(target.getId(), file);

			assertThat(counts.itemsSkipped()).isEqualTo(2);
			assertThat(exclusionsOf(target)).isEmpty();
		}
		finally {
			Files.deleteIfExists(file);
		}
	}
}
