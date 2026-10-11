package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.Course;
import com.cadence.api.courses.CourseRepository;
import com.cadence.api.races.dto.RaceCommentCreateRequest;
import com.cadence.api.races.dto.RaceCreateRequest;
import com.cadence.api.races.dto.RaceResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import com.cadence.api.sharing.UserRelationship;
import com.cadence.api.sharing.UserRelationshipRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's races/tests/test_views.py - priority/course_id CRUD round-trip,
 * computed parent_race_id/block_start/block_weeks, and race-comment permissions/threading
 * (self-or-coach-with-grant, unlike activity comments' read-gated model). */
class RaceControllerCourseAndCommentIntegrationTest extends IntegrationTest {

	@Autowired
	private RaceController raceController;
	@Autowired
	private RaceRepository raceRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("User");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private void authAsSelf(String id) {
		AuthContextHolder.set(AuthContext.self(id, Set.of("activities:read", "activities:write"), AuthContext.CredentialKind.OAUTH2));
	}

	private void authAsDelegated(String sub, String athleteId, Set<String> scopes) {
		AuthContextHolder.set(new AuthContext(sub, athleteId, scopes, AuthContext.CredentialKind.JWT));
	}

	private void grantCoach(User athlete, User coach) {
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.COACH);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);
	}

	@Test
	void createRaceDefaultsPriorityToA() {
		User athlete = newUser("races-crud-default@example.cc");
		authAsSelf(athlete.getId());

		RaceResponse response = raceController.createRace(
				new RaceCreateRequest("Spring Marathon", "2026-04-12", "run", null, null, null, null, null, null, null, null, null));

		assertThat(response.priority()).isEqualTo("A");
	}

	@Test
	void createRaceWithPriorityAndCourse() {
		User athlete = newUser("races-crud-course@example.cc");
		Course course = new Course();
		course.setAthlete(athlete);
		course.setName("Valencia");
		course.setSource(com.cadence.api.courses.CourseSource.GPX);
		course.setDistanceKm(42.2);
		course = courseRepository.save(course);
		authAsSelf(athlete.getId());

		RaceResponse response = raceController.createRace(new RaceCreateRequest(
				"Valencia Marathon", "2026-12-06", "run", null, null, null, null, null, null, null, "B", course.getId()));

		assertThat(response.priority()).isEqualTo("B");
		assertThat(response.courseId()).isEqualTo(course.getId());
	}

	@Test
	void listIncludesComputedBlockFieldsForAnARace() {
		User athlete = newUser("races-crud-block-a@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race.setPriority(RacePriority.A);
		raceRepository.save(race);
		authAsSelf(athlete.getId());

		var listed = raceController.listRaces().data().get(0);

		assertThat(listed.parentRaceId()).isNull();
		assertThat(listed.blockWeeks()).isEqualTo(16);
		assertThat(listed.blockStart()).isNotNull();
	}

	@Test
	void listIncludesParentRaceIdForBRaceInABlock() {
		User athlete = newUser("races-crud-block-b@example.cc");
		Race aRace = new Race();
		aRace.setAthlete(athlete);
		aRace.setName("Goal Marathon");
		aRace.setDate(LocalDate.of(2026, 10, 12));
		aRace.setPriority(RacePriority.A);
		raceRepository.save(aRace);

		Race bRace = new Race();
		bRace.setAthlete(athlete);
		bRace.setName("Tune-up Half");
		bRace.setDate(LocalDate.of(2026, 9, 14));
		bRace.setPriority(RacePriority.B);
		raceRepository.save(bRace);

		authAsSelf(athlete.getId());
		var byName = raceController.listRaces().data().stream()
				.collect(java.util.stream.Collectors.toMap(RaceResponse::name, r -> r));

		assertThat(byName.get("Tune-up Half").parentRaceId()).isEqualTo(aRace.getId());
	}

	@Test
	void athleteCanPostAndListComments() {
		User athlete = newUser("race-comments-athlete@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race.setPriority(RacePriority.A);
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		var created = raceController.createRaceComment(race.getId(), new RaceCommentCreateRequest("Feeling strong this week.", null, null));
		assertThat(created.role()).isEqualTo("athlete");

		assertThat(raceController.listRaceComments(race.getId()).data()).hasSize(1);
	}

	@Test
	void coachWithGrantPostsAsCoachRole() {
		User athlete = newUser("race-comments-coach-athlete@example.cc");
		User coach = newUser("race-comments-coach@example.cc");
		grantCoach(athlete, coach);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);
		authAsDelegated(coach.getId(), athlete.getId(), Set.of("activities:read", "activities:write", "coach"));

		var created = raceController.createRaceComment(race.getId(), new RaceCommentCreateRequest("Great block so far.", 3, null));

		assertThat(created.role()).isEqualTo("coach");
		assertThat(created.week()).isEqualTo(3);
	}

	@Test
	void outsiderWithoutGrantCannotComment() {
		User athlete = newUser("race-comments-outsider-athlete@example.cc");
		User outsider = newUser("race-comments-outsider@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);
		final String raceId = race.getId();
		authAsDelegated(outsider.getId(), athlete.getId(), Set.of("activities:read"));

		assertThatThrownBy(() -> raceController.createRaceComment(raceId, new RaceCommentCreateRequest("Uninvited.", null, null)))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void replyToAReplyIsRejected() {
		User athlete = newUser("race-comments-reply-reply@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);
		final String raceId = race.getId();
		authAsSelf(athlete.getId());

		var topLevel = raceController.createRaceComment(raceId, new RaceCommentCreateRequest("Top level", null, null));
		var reply = raceController.createRaceComment(raceId, new RaceCommentCreateRequest("A reply", null, topLevel.id()));

		assertThatThrownBy(() -> raceController.createRaceComment(
				raceId, new RaceCommentCreateRequest("Reply to a reply", null, reply.id())))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void authorCanDeleteOwnComment() {
		User athlete = newUser("race-comments-delete-own@example.cc");
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);
		authAsSelf(athlete.getId());

		var created = raceController.createRaceComment(race.getId(), new RaceCommentCreateRequest("Delete me", null, null));
		raceController.deleteRaceComment(race.getId(), created.id());

		assertThat(raceController.listRaceComments(race.getId()).data()).isEmpty();
	}

	@Test
	void cannotDeleteSomeoneElsesComment() {
		User athlete = newUser("race-comments-delete-other-athlete@example.cc");
		User coach = newUser("race-comments-delete-other-coach@example.cc");
		grantCoach(athlete, coach);
		Race race = new Race();
		race.setAthlete(athlete);
		race.setName("Goal Marathon");
		race.setDate(LocalDate.of(2026, 10, 12));
		race = raceRepository.save(race);
		final String raceId = race.getId();
		authAsSelf(athlete.getId());
		var created = raceController.createRaceComment(raceId, new RaceCommentCreateRequest("Not yours", null, null));

		authAsDelegated(coach.getId(), athlete.getId(), Set.of("activities:read", "coach"));
		assertThatThrownBy(() -> raceController.deleteRaceComment(raceId, created.id())).isInstanceOf(ForbiddenException.class);
	}
}
