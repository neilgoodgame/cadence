package com.cadence.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.admin.dto.AdminShoeCatalogEntryResponse;
import com.cadence.api.common.error.ConflictException;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Real brands (Nike, Hoka, ...) are seeded on startup for the Gear "add shoe" flow's catalog
// search to have real data - use a manufacturer name that can't collide with it.
class AdminShoeCatalogServiceIntegrationTest extends IntegrationTest {

	private static final String MANUFACTURER = "Testrunner Co";

	@Autowired
	private AdminShoeCatalogService service;

	@Autowired
	private AdminAuditLogService auditLogService;

	@Autowired
	private ShoeModelRepository shoeModelRepository;

	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	@Autowired
	private ShoeRepository shoeRepository;

	@Autowired
	private UserRepository userRepository;

	private User newAdmin(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Admin " + email);
		user.setPassword("irrelevant-for-this-test");
		user.setAdmin(true);
		return userRepository.save(user);
	}

	@Test
	void createOrAppendWithNewManufacturerAndModelCreatesEntryAndAuditRow() {
		User admin = newAdmin("shoe-new@example.cc");
		long auditBefore = auditLogService.list().size();

		AdminShoeCatalogEntryResponse response = service.createOrAppend(admin.getId(), MANUFACTURER, "Speedster", "4");

		assertThat(response.manufacturer()).isEqualTo(MANUFACTURER);
		assertThat(response.versions()).containsExactly("4");
		assertThat(auditLogService.list()).hasSize((int) auditBefore + 1);
		assertThat(auditLogService.list().get(0).action()).isEqualTo(CatalogAuditAction.ADDED);
	}

	@Test
	void createOrAppendWithExistingManufacturerAndModelAppendsVersion() {
		User admin = newAdmin("shoe-append@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel("Speedster2");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v2 = new ShoeModelVersion();
		v2.setShoeModel(shoeModel);
		v2.setVersion("2");
		shoeModelVersionRepository.save(v2);

		AdminShoeCatalogEntryResponse response = service.createOrAppend(admin.getId(), MANUFACTURER, "Speedster2", "3");

		assertThat(response.versions()).containsExactlyInAnyOrder("2", "3");
	}

	@Test
	void createOrAppendMatchesExistingModelCaseInsensitively() {
		User admin = newAdmin("shoe-case@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel("Speedster3");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v2 = new ShoeModelVersion();
		v2.setShoeModel(shoeModel);
		v2.setVersion("2");
		shoeModelVersionRepository.save(v2);

		AdminShoeCatalogEntryResponse response =
				service.createOrAppend(admin.getId(), MANUFACTURER.toLowerCase(), "speedster3", "3");

		assertThat(response.versions()).containsExactlyInAnyOrder("2", "3");
	}

	@Test
	void appendVersionRejectsDuplicateCaseInsensitively() {
		User admin = newAdmin("shoe-dup@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel("Trailster");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v3 = new ShoeModelVersion();
		v3.setShoeModel(shoeModel);
		v3.setVersion("3");
		shoeModelVersionRepository.save(v3);

		assertThatThrownBy(() -> service.appendVersion(admin.getId(), shoeModel.getId(), "3"))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void deleteWithNoShoesInUseCascadesAndLogs() {
		User admin = newAdmin("shoe-delete@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel("Trackster");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v3 = new ShoeModelVersion();
		v3.setShoeModel(shoeModel);
		v3.setVersion("3");
		shoeModelVersionRepository.save(v3);
		String shoeModelId = shoeModel.getId();

		service.delete(admin.getId(), shoeModelId);

		assertThat(shoeModelRepository.findById(shoeModelId)).isEmpty();
		assertThat(shoeModelVersionRepository.findByShoeModelId(shoeModelId)).isEmpty();
		assertThat(auditLogService.list().get(0).action()).isEqualTo(CatalogAuditAction.REMOVED);
		assertThat(auditLogService.list().get(0).description()).isEqualTo(MANUFACTURER + " Trackster");
	}

	@Test
	void deleteBlockedWhenAShoeReferencesAVersion() {
		User admin = newAdmin("shoe-blocked@example.cc");
		User athlete = newAdmin("shoe-athlete@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel("Trackster2");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v3 = new ShoeModelVersion();
		v3.setShoeModel(shoeModel);
		v3.setVersion("3");
		shoeModelVersionRepository.save(v3);

		Shoe shoe = new Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(v3);
		shoe.setName("Race day");
		shoeRepository.save(shoe);

		String shoeModelId = shoeModel.getId();
		long auditBefore = auditLogService.list().size();

		assertThatThrownBy(() -> service.delete(admin.getId(), shoeModelId)).isInstanceOf(ConflictException.class);

		assertThat(shoeModelRepository.findById(shoeModelId)).isPresent();
		assertThat(shoeModelVersionRepository.findByShoeModelId(shoeModelId)).hasSize(1);
		assertThat(auditLogService.list()).hasSize((int) auditBefore);
	}

	@Test
	void searchFiltersCaseInsensitively() {
		// A manufacturer unique to this one test - MANUFACTURER ("Testrunner Co") is reused by
		// every other test in this class, and IntegrationTest doesn't roll back between tests,
		// so a search on that shared name would also match every other test's rows.
		String manufacturer = "Zzz Search Brand";
		User admin = newAdmin("shoe-search@example.cc");
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(manufacturer);
		shoeModel.setModel("Speedster4");
		shoeModel.setCreatedBy(admin);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion v1 = new ShoeModelVersion();
		v1.setShoeModel(shoeModel);
		v1.setVersion("1");
		shoeModelVersionRepository.save(v1);

		var results = service.list(manufacturer.toLowerCase());

		assertThat(results).hasSize(1);
		assertThat(results.get(0).manufacturer()).isEqualTo(manufacturer);
	}
}
