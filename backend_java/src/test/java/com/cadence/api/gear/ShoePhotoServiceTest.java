package com.cadence.api.gear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.error.PayloadTooLargeException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.dto.ShoePhotoResponse;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

// The gear app ships with a seeded starter catalog (V19__seed_shoe_catalog.sql, real brands
// like Nike/Hoka/Adidas) present in every test DB - use a manufacturer that can't collide with
// it, same reasoning as AdminShoeCatalogServiceIntegrationTest. Every email is prefixed
// "shoe-photo-" to rule out a cross-class collision in IntegrationTest's shared, never-rolled-
// back Postgres (see the gear-shoe-import- collision fixed earlier for ShoeServiceImportTest).
class ShoePhotoServiceTest extends IntegrationTest {

	private static final String MANUFACTURER = "Testrunner Co";

	@Autowired
	private ShoePhotoService shoePhotoService;

	@Autowired
	private ShoePhotoRepository shoePhotoRepository;

	@Autowired
	private ShoeRepository shoeRepository;

	@Autowired
	private ShoeModelRepository shoeModelRepository;

	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	@Autowired
	private UserRepository userRepository;

	private Shoe newShoe(String email, String model, int km) {
		User athlete = new User();
		athlete.setEmail(email);
		athlete.setName("Athlete " + email);
		athlete.setPassword("irrelevant-for-this-test");
		userRepository.save(athlete);

		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer(MANUFACTURER);
		shoeModel.setModel(model);
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion("3");
		shoeModelVersionRepository.save(smv);

		Shoe shoe = new Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(smv);
		shoe.setColourway("Black");
		shoe.setName("Race day " + model);
		shoe.setKm(km);
		return shoeRepository.save(shoe);
	}

	private MockMultipartFile image() {
		return new MockMultipartFile("image", "sole.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());
	}

	@Test
	void uploadWithExplicitFields() {
		Shoe shoe = newShoe("shoe-photo-explicit@example.cc", "Speedwing", 150);

		ShoePhotoResponse response =
				shoePhotoService.upload(shoe, image(), LocalDate.of(2026, 3, 1), 180, "lateral heel wear starting");

		assertThat(response.shoeId()).isEqualTo(shoe.getId());
		assertThat(response.contentType()).isEqualTo("image/jpeg");
		assertThat(response.takenOn()).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(response.km()).isEqualTo(180);
		assertThat(response.notes()).isEqualTo("lateral heel wear starting");

		ShoePhoto saved = shoePhotoRepository.findById(response.id()).orElseThrow();
		assertThat(saved.getImage()).isEqualTo("fake-jpeg-bytes".getBytes());
	}

	@Test
	void uploadDefaultsTakenOnToTodayAndKmToTheShoesCurrentKm() {
		Shoe shoe = newShoe("shoe-photo-defaults@example.cc", "Speedwing2", 150);

		ShoePhotoResponse response = shoePhotoService.upload(shoe, image(), null, null, null);

		assertThat(response.takenOn()).isEqualTo(LocalDate.now());
		assertThat(response.km()).isEqualTo(150);
		assertThat(response.notes()).isEmpty();
	}

	@Test
	void rejectsADisallowedContentType() {
		Shoe shoe = newShoe("shoe-photo-badtype@example.cc", "Speedwing3", 100);
		MockMultipartFile gif = new MockMultipartFile("image", "sole.gif", "image/gif", "x".getBytes());

		assertThatThrownBy(() -> shoePhotoService.upload(shoe, gif, null, null, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void acceptsHeic() {
		Shoe shoe = newShoe("shoe-photo-heic@example.cc", "Speedwing4", 100);
		MockMultipartFile heic = new MockMultipartFile("image", "sole.heic", "image/heic", "fake-heic".getBytes());

		ShoePhotoResponse response = shoePhotoService.upload(shoe, heic, null, null, null);

		assertThat(response.contentType()).isEqualTo("image/heic");
	}

	@Test
	void rejectsAnOversizedImage() {
		Shoe shoe = newShoe("shoe-photo-oversized@example.cc", "Speedwing5", 100);
		byte[] tooBig = new byte[(int) ShoePhotoService.MAX_IMAGE_BYTES + 1];
		MockMultipartFile big = new MockMultipartFile("image", "sole.jpg", "image/jpeg", tooBig);

		assertThatThrownBy(() -> shoePhotoService.upload(shoe, big, null, null, null))
				.isInstanceOf(PayloadTooLargeException.class);
	}

	@Test
	void listOrdersByTakenOn() {
		Shoe shoe = newShoe("shoe-photo-list@example.cc", "Speedwing6", 100);
		shoePhotoService.upload(shoe, image(), LocalDate.of(2026, 3, 1), null, null);
		shoePhotoService.upload(shoe, image(), LocalDate.of(2026, 1, 1), null, null);

		List<ShoePhotoResponse> photos = shoePhotoService.list(shoe.getId());

		assertThat(photos).extracting(ShoePhotoResponse::takenOn)
				.containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1));
	}

	@Test
	void deleteRemovesThePhoto() {
		Shoe shoe = newShoe("shoe-photo-delete@example.cc", "Speedwing7", 100);
		ShoePhotoResponse response = shoePhotoService.upload(shoe, image(), null, null, null);

		shoePhotoService.delete(response.id());

		assertThat(shoePhotoRepository.findById(response.id())).isEmpty();
	}

	@Test
	void deletingAShoeCascadesToItsPhotos() {
		Shoe shoe = newShoe("shoe-photo-cascade@example.cc", "Speedwing8", 100);
		ShoePhotoResponse response = shoePhotoService.upload(shoe, image(), null, null, null);

		shoeRepository.deleteById(shoe.getId());

		assertThat(shoePhotoRepository.findById(response.id())).isEmpty();
	}

	@Test
	void getWithShoeAndAthleteLoadsBothEagerly() {
		Shoe shoe = newShoe("shoe-photo-eager@example.cc", "Speedwing9", 100);
		ShoePhotoResponse response = shoePhotoService.upload(shoe, image(), null, null, null);

		ShoePhoto loaded = shoePhotoService.getWithShoeAndAthlete(response.id());

		assertThat(loaded.getShoe().getAthlete().getEmail()).isEqualTo("shoe-photo-eager@example.cc");
	}
}
