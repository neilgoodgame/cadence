package com.cadence.api.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.sharing.dto.CreateShareRequest;
import com.cadence.api.sharing.dto.CreateVirtualCoachRequest;
import com.cadence.api.sharing.dto.VirtualCoachCreatedResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.tokens.AccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import com.cadence.api.workouts.Workout;
import com.cadence.api.workouts.WorkoutRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A virtual coach's delegated token needs to make {@code GET /v1/workouts} (a list endpoint,
 * which resolves the athlete to query via {@code AccessGuard.effectiveAthleteId()} -
 * {@code AuthContext.athleteId}) return the *athlete's* workouts, not an empty list of the
 * coach's own - unlike a single-resource-by-id endpoint (e.g. {@code GET /v1/workouts/{id}}),
 * which derives the athlete from the resource's own owner and checks {@code sub} against it via
 * {@code PermissionService}, and would (misleadingly) already pass for a coach's ordinary
 * self-scoped token given a live relationship, proving nothing about this delegation path.
 */
@AutoConfigureMockMvc
class VirtualCoachDelegationIntegrationTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SharingService sharingService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private WorkoutRepository workoutRepository;

	@Autowired
	private AccessTokenService accessTokenService;

	@Autowired
	private UserRelationshipRepository userRelationshipRepository;

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Workout newWorkout(User athlete, String name) {
		Workout workout = new Workout();
		workout.setCreatedBy(athlete);
		workout.setName(name);
		workout.setSport(Sport.BIKE);
		return workoutRepository.save(workout);
	}

	private VirtualCoachCreatedResponse createVirtualCoachAs(User athlete, List<String> scopes) {
		AuthContextHolder.set(AuthContext.self(athlete.getId(), Set.of(), AuthContext.CredentialKind.OAUTH2));
		try {
			return sharingService.createVirtualCoach(athlete, new CreateVirtualCoachRequest("Claude.ai", scopes));
		}
		finally {
			AuthContextHolder.clear();
		}
	}

	@Test
	void aVirtualCoachsTokenListsTheAthletesWorkouts() throws Exception {
		User athlete = newAthlete("virtual-coach-athlete@example.cc");
		Workout workout = newWorkout(athlete, "Z2 long ride");
		VirtualCoachCreatedResponse created =
				createVirtualCoachAs(athlete, List.of("activities:read", "workouts:write", "calendar:write"));

		mockMvc.perform(get("/v1/workouts").header("Authorization", "Bearer " + created.token().secret()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(workout.getId()));
	}

	@Test
	void aPlainSelfScopedTokenNeverSeesAnotherAthletesWorkouts() throws Exception {
		User athlete = newAthlete("virtual-coach-control-athlete@example.cc");
		newWorkout(athlete, "Z2 long ride");
		User coach = newAthlete("virtual-coach-control-coach@example.cc");

		// An ordinary (non-delegated) personal access token - no athleteId, so `create` leaves
		// PersonalAccessToken.delegatedAthleteId null and it resolves to self, same as before
		// delegation existed.
		AccessTokenService.CreatedToken token = accessTokenService.create(
				coach, new CreateAccessTokenRequest("Ordinary token", List.of("activities:read"), null, null), Set.of());

		mockMvc.perform(get("/v1/workouts").header("Authorization", "Bearer " + token.secret()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	void revokingTheShareInvalidatesTheVirtualCoachsToken() throws Exception {
		User athlete = newAthlete("virtual-coach-revoke-athlete@example.cc");
		newWorkout(athlete, "Z2 long ride");
		VirtualCoachCreatedResponse created = createVirtualCoachAs(athlete, List.of("activities:read", "workouts:write"));

		sharingService.deleteShare(created.share().id());

		mockMvc.perform(get("/v1/workouts").header("Authorization", "Bearer " + created.token().secret()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void createVirtualCoachIssuesAUsablePasswordThatLogsIn() throws Exception {
		User athlete = newAthlete("virtual-coach-password-athlete@example.cc");
		VirtualCoachCreatedResponse created = createVirtualCoachAs(athlete, List.of("activities:read"));

		mockMvc.perform(post("/v1/auth/login").contentType("application/json")
						.content("{\"email\":\"" + created.email() + "\",\"password\":\"" + created.password() + "\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void revokingTheShareDeletesTheWholeVirtualAccount() {
		User athlete = newAthlete("virtual-coach-revoke-account-athlete@example.cc");
		VirtualCoachCreatedResponse created = createVirtualCoachAs(athlete, List.of("activities:read"));
		String coachId = userRelationshipRepository.findByIdWithUsers(created.share().id()).orElseThrow().getGrantee().getId();

		sharingService.deleteShare(created.share().id());

		assertThat(userRepository.findById(coachId)).isEmpty();
	}

	@Test
	void aViewerOnlyRelationshipCannotMintADelegatedTokenWithWriteScopes() {
		User athlete = newAthlete("delegation-viewer-athlete@example.cc");
		User coach = newAthlete("delegation-viewer-coach@example.cc");
		UserRelationship relationship = new UserRelationship();
		relationship.setOwner(athlete);
		relationship.setGrantee(coach);
		relationship.setRole(ShareRole.VIEWER);
		relationship.setStatus(ShareStatus.ACTIVE);
		userRelationshipRepository.save(relationship);

		assertThatThrownBy(() -> accessTokenService.create(coach,
				new CreateAccessTokenRequest("Should fail", List.of("workouts:write"), null, athlete.getId()), Set.of()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void virtualAccountsCannotBeInvitedAsAnOrdinaryShare() {
		User athlete = newAthlete("delegation-invite-athlete@example.cc");
		VirtualCoachCreatedResponse created = createVirtualCoachAs(athlete, List.of("activities:read"));
		User otherAthlete = newAthlete("delegation-invite-other-athlete@example.cc");
		User virtualCoach = userRelationshipRepository.findByIdWithUsers(created.share().id()).orElseThrow().getGrantee();

		assertThatThrownBy(
				() -> sharingService.createShare(otherAthlete, new CreateShareRequest(virtualCoach.getEmail(), ShareRole.COACH)))
				.isInstanceOf(ValidationException.class);
	}
}
