package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.gear.Shoe;
import com.cadence.api.gear.ShoeModel;
import com.cadence.api.gear.ShoeModelRepository;
import com.cadence.api.gear.ShoeModelVersion;
import com.cadence.api.gear.ShoeModelVersionRepository;
import com.cadence.api.gear.ShoeRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Covers ActivityService.updateActivity's bike_id/shoe_id branches - link, unlink, and
 * rejecting another athlete's gear - mirroring ActivityServiceMatchPreferencesTest's style for
 * the same method's workout_id branch. */
class ActivityServiceGearLinkTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private BikeRepository bikeRepository;
	@Autowired
	private ShoeRepository shoeRepository;
	@Autowired
	private ShoeModelRepository shoeModelRepository;
	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Session");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		return activityRepository.save(activity);
	}

	private Bike newBike(User athlete, String name) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName(name);
		return bikeRepository.save(bike);
	}

	private Shoe newShoe(User athlete, String name) {
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("Nike");
		shoeModel.setModel("Vaporfly");
		shoeModelRepository.save(shoeModel);
		ShoeModelVersion smv = new ShoeModelVersion();
		smv.setShoeModel(shoeModel);
		smv.setVersion("3");
		shoeModelVersionRepository.save(smv);

		Shoe shoe = new Shoe();
		shoe.setAthlete(athlete);
		shoe.setShoeModelVersion(smv);
		shoe.setName(name);
		return shoeRepository.save(shoe);
	}

	@Test
	void linksBike() {
		User athlete = newAthlete("gear-link-bike@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		Bike bike = newBike(athlete, "Tarmac SL7");

		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getBike().getId()).isEqualTo(bike.getId());
	}

	@Test
	void unlinksBike() {
		User athlete = newAthlete("gear-unlink-bike@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		Bike bike = newBike(athlete, "Tarmac SL7");
		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Collections.singletonMap("bike_id", null));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getBike()).isNull();
	}

	@Test
	void rejectsAnotherAthletesBike() {
		User athlete = newAthlete("gear-reject-bike@example.cc");
		User other = newAthlete("gear-reject-bike-other@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE);
		Bike bike = newBike(other, "Not mine");

		assertThatThrownBy(() -> activityService.updateActivity(activity, Map.of("bike_id", bike.getId())))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void linksShoe() {
		User athlete = newAthlete("gear-link-shoe@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN);
		Shoe shoe = newShoe(athlete, "Race Day Flyers");

		activityService.updateActivity(activity, Map.of("shoe_id", shoe.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getShoe().getId()).isEqualTo(shoe.getId());
	}

	@Test
	void unlinksShoe() {
		User athlete = newAthlete("gear-unlink-shoe@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN);
		Shoe shoe = newShoe(athlete, "Race Day Flyers");
		activityService.updateActivity(activity, Map.of("shoe_id", shoe.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Collections.singletonMap("shoe_id", null));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getShoe()).isNull();
	}

	@Test
	void rejectsAnotherAthletesShoe() {
		User athlete = newAthlete("gear-reject-shoe@example.cc");
		User other = newAthlete("gear-reject-shoe-other@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN);
		Shoe shoe = newShoe(other, "Not mine");

		assertThatThrownBy(() -> activityService.updateActivity(activity, Map.of("shoe_id", shoe.getId())))
				.isInstanceOf(ValidationException.class);
	}
}
