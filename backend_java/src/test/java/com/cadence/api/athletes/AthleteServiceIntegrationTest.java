package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's athletes/tests.py:test_update_with_{no_,}existing_zone_set_reports_*recompute exactly. */
class AthleteServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private AthleteService athleteService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneSetRepository zoneSetRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void reportsNoRecomputeWhenNoZoneSetExistsYet() {
		User athlete = newAthlete("no-zoneset@example.cc");

		var recomputed = athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, 280, null, null, null, null));

		assertThat(recomputed).isEmpty();
	}

	@Test
	void reportsRecomputeWhenAZoneSetAlreadyExists() {
		User athlete = newAthlete("existing-zoneset@example.cc");
		ZoneSet zoneSet = new ZoneSet();
		zoneSet.setAthlete(athlete);
		zoneSet.setType(ZoneType.BIKE_POWER);
		zoneSet.setZones(ZoneService.DEFAULT_ZONES);
		zoneSetRepository.save(zoneSet);

		var recomputed = athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, 280, null, null, null, null));

		assertThat(recomputed).containsExactly(ZoneType.BIKE_POWER);
	}
}
