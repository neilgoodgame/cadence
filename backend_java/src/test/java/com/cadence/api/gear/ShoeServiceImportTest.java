package com.cadence.api.gear;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.gear.dto.ShoeImportRequest;
import com.cadence.api.gear.dto.ShoeImportResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// The gear app ships with a seeded starter catalog (V19__seed_shoe_catalog.sql, real brands
// like Nike/Hoka/Adidas) present in every test DB - use a manufacturer that can't collide with
// it, same reasoning as AdminShoeCatalogServiceIntegrationTest.
class ShoeServiceImportTest extends IntegrationTest {

	private static final String MANUFACTURER = "Testrunner Co";

	@Autowired
	private ShoeService shoeService;

	@Autowired
	private ShoeRepository shoeRepository;

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

	private ShoeModelVersion newCatalogEntry(String model, String version) {
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel(model);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion(version);
		shoeModelVersionRepository.save(smv);
		return smv;
	}

	@Test
	void matchesAnExistingCatalogEntryAndRoundsDistance() {
		User athlete = newAthlete("gear-shoe-import-match@example.cc");
		ShoeModelVersion smv = newCatalogEntry("Speedwing", "3");

		ShoeImportResponse response = shoeService.importShoes(athlete,
				List.of(new ShoeImportRequest.Entry(MANUFACTURER, "Speedwing", "3", null, 257.9)), false);

		assertThat(response.shoesCreated()).isEqualTo(1);
		assertThat(response.catalogModelsCreated()).isZero();
		assertThat(response.catalogVersionsCreated()).isZero();
		assertThat(response.skippedNoCatalogMatch()).isZero();
		assertThat(response.skippedAlreadyInGear()).isZero();
		Shoe shoe = shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(athlete.getId()).get(0);
		assertThat(shoe.getShoeModelVersion().getId()).isEqualTo(smv.getId());
		assertThat(shoe.getKm()).isEqualTo(258);
	}

	@Test
	void nonAdminSkipsAnUnmatchedEntryWithoutTouchingTheCatalog() {
		User athlete = newAthlete("gear-shoe-import-skip@example.cc");
		long modelsBefore = shoeModelRepository.count();

		ShoeImportResponse response = shoeService.importShoes(athlete,
				List.of(new ShoeImportRequest.Entry(MANUFACTURER, "Brand New Model", null, null, null)), false);

		assertThat(response.shoesCreated()).isZero();
		assertThat(response.skippedNoCatalogMatch()).isEqualTo(1);
		assertThat(shoeModelRepository.count()).isEqualTo(modelsBefore);
		assertThat(shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(athlete.getId())).isEmpty();
	}

	@Test
	void adminCreatesTheMissingModelAndVersionThenTheShoe() {
		User admin = newAthlete("gear-shoe-import-admin@example.cc");

		ShoeImportResponse response = shoeService.importShoes(admin,
				List.of(new ShoeImportRequest.Entry(MANUFACTURER, "Brand New Model 2", null, null, 192.2)), true);

		assertThat(response.shoesCreated()).isEqualTo(1);
		assertThat(response.catalogModelsCreated()).isEqualTo(1);
		assertThat(response.catalogVersionsCreated()).isEqualTo(1);
		Shoe shoe = shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(admin.getId()).get(0);
		assertThat(shoe.getShoeModelVersion().getShoeModel().getManufacturer()).isEqualTo(MANUFACTURER);
		assertThat(shoe.getKm()).isEqualTo(192);
	}

	@Test
	void adminOnlyCreatesTheVersionWhenTheModelAlreadyExists() {
		User admin = newAthlete("gear-shoe-import-admin-version@example.cc");
		newCatalogEntry("Speedwing2", "3");
		long modelsBefore = shoeModelRepository.count();

		ShoeImportResponse response = shoeService.importShoes(admin,
				List.of(new ShoeImportRequest.Entry(MANUFACTURER, "Speedwing2", "4", null, null)), true);

		assertThat(response.catalogModelsCreated()).isZero();
		assertThat(response.catalogVersionsCreated()).isEqualTo(1);
		assertThat(shoeModelRepository.count()).isEqualTo(modelsBefore);
	}

	@Test
	void reimportingTheSameFileIsIdempotent() {
		User athlete = newAthlete("gear-shoe-import-idempotent@example.cc");
		newCatalogEntry("Speedwing3", "3");
		List<ShoeImportRequest.Entry> entries =
				List.of(new ShoeImportRequest.Entry(MANUFACTURER, "Speedwing3", "3", "Red", null));

		ShoeImportResponse first = shoeService.importShoes(athlete, entries, false);
		assertThat(first.shoesCreated()).isEqualTo(1);

		ShoeImportResponse second = shoeService.importShoes(athlete, entries, false);
		assertThat(second.shoesCreated()).isZero();
		assertThat(second.skippedAlreadyInGear()).isEqualTo(1);
		assertThat(shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(athlete.getId())).hasSize(1);
	}

	@Test
	void differentColourwaysOfTheSameVersionBothImport() {
		User athlete = newAthlete("gear-shoe-import-colourways@example.cc");
		newCatalogEntry("Speedwing4", "3");

		ShoeImportResponse response = shoeService.importShoes(athlete,
				List.of(
						new ShoeImportRequest.Entry(MANUFACTURER, "Speedwing4", "3", "Red", null),
						new ShoeImportRequest.Entry(MANUFACTURER, "Speedwing4", "3", null, null)),
				false);

		assertThat(response.shoesCreated()).isEqualTo(2);
		assertThat(shoeRepository.findByAthleteIdAndRetiredFalseOrderByIdDesc(athlete.getId())).hasSize(2);
	}
}
