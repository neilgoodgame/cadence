package com.cadence.api.gear;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.gear.dto.ShoeCreateRequest;
import com.cadence.api.gear.dto.ShoeResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// The gear app ships with a seeded starter catalog (V19__seed_shoe_catalog.sql, real brands
// like Nike/Hoka/Adidas) present in every test DB - use a manufacturer that can't collide with
// it, same reasoning as AdminShoeCatalogServiceIntegrationTest. Every email/model name here is
// prefixed "shoe-create-"/"CreateTest" to rule out a cross-class collision in IntegrationTest's
// shared, never-rolled-back Postgres (see feedback_java_integrationtest_fixture_collisions).
class ShoeServiceCreateTest extends IntegrationTest {

	private static final String MANUFACTURER = "Testrunner Co";

	@Autowired
	private ShoeService shoeService;

	@Autowired
	private ShoeModelRepository shoeModelRepository;

	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private ShoeModelVersion newCatalogEntry(String model) {
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel(model);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion("1");
		shoeModelVersionRepository.save(smv);
		return smv;
	}

	@Test
	void explicitZeroLimitKmIsNotOverriddenByTheDefault() {
		User athlete = newAthlete("shoe-create-explicit-zero@example.cc");
		ShoeModelVersion smv = newCatalogEntry("CreateTestShoeA");

		ShoeResponse response =
				shoeService.toResponse(shoeService.createShoe(athlete, new ShoeCreateRequest(smv.getId(), "Black", null, 0, null)));

		assertThat(response.limitKm()).isZero();
	}

	@Test
	void omittedLimitKmDefaultsToTheAthletesPreference() {
		User athlete = newAthlete("shoe-create-omitted@example.cc");
		athlete.setDefaultShoeLimitKm(1000);
		userRepository.save(athlete);
		ShoeModelVersion smv = newCatalogEntry("CreateTestShoeB");

		ShoeResponse response = shoeService.toResponse(
				shoeService.createShoe(athlete, new ShoeCreateRequest(smv.getId(), "Black", null, null, null)));

		assertThat(response.limitKm()).isEqualTo(1000);
	}
}
