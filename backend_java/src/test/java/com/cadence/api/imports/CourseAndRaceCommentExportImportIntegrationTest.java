package com.cadence.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.courses.CourseSource;
import com.cadence.api.export.ExportWriter;
import com.cadence.api.races.Race;
import com.cadence.api.races.RaceComment;
import com.cadence.api.races.RaceCommentRepository;
import com.cadence.api.races.RaceCommentRole;
import com.cadence.api.races.RacePriority;
import com.cadence.api.races.RaceRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/** Courses and race comments are new to this export/import round trip (not covered by the
 * existing ExportWriterIntegrationTest/ImportReaderIntegrationTest fixtures) - mirrors the
 * Python backend's CourseAndRaceCommentExportImportTests. */
class CourseAndRaceCommentExportImportIntegrationTest extends IntegrationTest {

	@Autowired
	private ExportWriter exportWriter;
	@Autowired
	private ImportReader importReader;
	@Autowired
	private JsonMapper jsonMapper;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private RaceCommentRepository raceCommentRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void courseAndRaceCommentsSurviveARoundTrip() throws IOException {
		User source = newUser("course-roundtrip-source@example.cc");
		User target = newUser("course-roundtrip-target@example.cc");

		Course course = new Course();
		course.setAthlete(source);
		course.setName("Valencia Marathon");
		course.setLocationName("Valencia, Spain");
		course.setSource(CourseSource.GPX);
		course.setDistanceKm(42.2);
		course.setLoop(false);
		course.setRoutePolyline(List.of(List.of(39.47, -0.38), List.of(39.48, -0.37)));
		course.setElevationProfile(List.of(List.of(0.0, 10.0), List.of(42.2, 12.0)));
		course = courseRepository.save(course);

		Race race = new Race();
		race.setAthlete(source);
		race.setName("Valencia Marathon");
		race.setDate(LocalDate.of(2026, 12, 6));
		race.setPriority(RacePriority.A);
		race.setCourse(course);
		race.setDistanceKm(42.2);
		race.setTargetPower(250);
		race = raceRepository.save(race);

		RaceComment topLevel = new RaceComment();
		topLevel.setRace(race);
		topLevel.setAuthor(source);
		topLevel.setRole(RaceCommentRole.ATHLETE);
		topLevel.setText("Feeling ready for this one.");
		topLevel = raceCommentRepository.save(topLevel);

		RaceComment reply = new RaceComment();
		reply.setRace(race);
		reply.setAuthor(source);
		reply.setRole(RaceCommentRole.COACH);
		reply.setText("Great block.");
		reply.setParent(topLevel);
		reply.setWeek(10);
		raceCommentRepository.save(reply);

		Path file = Files.createTempFile("course-roundtrip-test", ".json.gz");
		try {
			try (JsonGenerator generator = jsonMapper.createGenerator(
					new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), JsonEncoding.UTF8)) {
				exportWriter.write(source.getId(), null, generator);
			}

			var counts = importReader.read(target.getId(), file);

			assertThat(counts.coursesImported()).isEqualTo(1);
			assertThat(counts.racesImported()).isEqualTo(1);
			assertThat(counts.raceCommentsImported()).isEqualTo(2);

			Course importedCourse = courseRepository.findByAthleteId(target.getId()).get(0);
			assertThat(importedCourse.getName()).isEqualTo("Valencia Marathon");
			assertThat(importedCourse.getDistanceKm()).isEqualTo(42.2);
			assertThat(importedCourse.getId()).isNotEqualTo(course.getId());

			Race importedRace = raceRepository.findByAthleteIdOrderByDateAsc(target.getId()).get(0);
			assertThat(importedRace.getPriority()).isEqualTo(RacePriority.A);
			assertThat(importedRace.getCourse().getId()).isEqualTo(importedCourse.getId());
			assertThat(importedRace.getTargetPower()).isEqualTo(250);

			List<RaceComment> comments = raceCommentRepository.findByRaceIdOrderByCreatedAt(importedRace.getId());
			assertThat(comments).hasSize(2);
			RaceComment importedTop = comments.get(0);
			RaceComment importedReply = comments.get(1);
			assertThat(importedTop.getParent()).isNull();
			assertThat(importedReply.getParent().getId()).isEqualTo(importedTop.getId());
			assertThat(importedReply.getWeek()).isEqualTo(10);
			// Author identity isn't carried over - every imported comment is attributed to the
			// importing athlete (see ImportReader.importRaceComments).
			assertThat(importedTop.getAuthor().getId()).isEqualTo(target.getId());
			assertThat(importedReply.getAuthor().getId()).isEqualTo(target.getId());
			assertThat(importedReply.getRole()).isEqualTo(RaceCommentRole.COACH);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}
}
