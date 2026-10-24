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
		return newActivity(athlete, sport, 0);
	}

	private Activity newActivity(User athlete, Sport sport, double distanceKm) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Session");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setDistanceKm(distanceKm);
		return activityRepository.save(activity);
	}

	private Bike newBike(User athlete, String name) {
		return newBike(athlete, name, 0);
	}

	private Bike newBike(User athlete, String name, int distanceKm) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName(name);
		bike.setDistanceKm(distanceKm);
		return bikeRepository.save(bike);
	}

	private Shoe newShoe(User athlete, String name) {
		return newShoe(athlete, name, 0);
	}

	private Shoe newShoe(User athlete, String name, int km) {
		// Fake, distinctive manufacturer - IntegrationTest shares one non-rolled-back Postgres
		// across the whole run, so a real brand name would collide with Flyway-seeded catalog
		// data (see feedback_java_integrationtest_fixture_collisions.md-style gotcha). "Vendrix"
		// specifically avoids McpToolsIntegrationTest's own "Zzyzx"/"Qorvex" fixtures, whose
		// catalog-search test matches any manufacturer containing "zzyzx".
		ShoeModel shoeModel = new ShoeModel();
		shoeModel.setManufacturer("Vendrix");
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
		shoe.setKm(km);
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

	@Test
	void linkingBikeAddsActivityDistanceToBikeMileage() {
		User athlete = newAthlete("gear-mileage-link-bike@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 42.0);
		Bike bike = newBike(athlete, "Tarmac SL7", 100);

		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
	}

	@Test
	void unlinkingBikeSubtractsActivityDistance() {
		User athlete = newAthlete("gear-mileage-unlink-bike@example.cc");
		Bike bike = newBike(athlete, "Tarmac SL7", 142);
		Activity activity = newActivity(athlete, Sport.BIKE, 42.0);
		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Collections.singletonMap("bike_id", null));

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
	}

	@Test
	void changingBikeMovesTheDistance() {
		User athlete = newAthlete("gear-mileage-change-bike@example.cc");
		Bike oldBike = newBike(athlete, "Old bike", 142);
		Bike newBike = newBike(athlete, "New bike", 0);
		Activity activity = newActivity(athlete, Sport.BIKE, 42.0);
		activityService.updateActivity(activity, Map.of("bike_id", oldBike.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Map.of("bike_id", newBike.getId()));

		assertThat(bikeRepository.findById(oldBike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
		assertThat(bikeRepository.findById(newBike.getId()).orElseThrow().getDistanceKm()).isEqualTo(42);
	}

	@Test
	void relinkingTheSameBikeDoesNotDoubleCount() {
		User athlete = newAthlete("gear-mileage-relink-bike@example.cc");
		Bike bike = newBike(athlete, "Tarmac SL7", 100);
		Activity activity = newActivity(athlete, Sport.BIKE, 42.0);
		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Map.of("bike_id", bike.getId()));

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
	}

	@Test
	void linkingShoeAddsActivityDistanceToShoeMileage() {
		User athlete = newAthlete("gear-mileage-link-shoe@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN, 10.4);
		Shoe shoe = newShoe(athlete, "Race Day Flyers", 50);

		activityService.updateActivity(activity, Map.of("shoe_id", shoe.getId()));

		assertThat(shoeRepository.findById(shoe.getId()).orElseThrow().getKm()).isEqualTo(60);
	}

	@Test
	void unlinkingShoeSubtractsActivityDistance() {
		User athlete = newAthlete("gear-mileage-unlink-shoe@example.cc");
		Shoe shoe = newShoe(athlete, "Race Day Flyers", 60);
		Activity activity = newActivity(athlete, Sport.RUN, 10.4);
		activityService.updateActivity(activity, Map.of("shoe_id", shoe.getId()));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		activityService.updateActivity(activity, Collections.singletonMap("shoe_id", null));

		assertThat(shoeRepository.findById(shoe.getId()).orElseThrow().getKm()).isEqualTo(60);
	}

	@Test
	void deletingActivitySubtractsLinkedGearMileage() {
		User athlete = newAthlete("gear-mileage-delete-activity@example.cc");
		Bike bike = newBike(athlete, "Tarmac SL7", 142);
		Shoe shoe = newShoe(athlete, "Race Day Flyers", 60);
		Activity activity = newActivity(athlete, Sport.BIKE, 42.0);
		activityService.updateActivity(activity, Map.of("bike_id", bike.getId(), "shoe_id", shoe.getId()));

		activityService.deleteActivity(activity.getId());

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(142);
		assertThat(shoeRepository.findById(shoe.getId()).orElseThrow().getKm()).isEqualTo(60);
	}

	@Test
	void deletingAllActivitiesAggregatesGearMileageButLeavesOtherAthletesAlone() {
		User athlete = newAthlete("gear-mileage-delete-all@example.cc");
		User other = newAthlete("gear-mileage-delete-all-other@example.cc");
		Bike bike = newBike(athlete, "Tarmac SL7", 200);
		Activity first = newActivity(athlete, Sport.BIKE, 40.3);
		Activity second = newActivity(athlete, Sport.BIKE, 19.6);
		activityService.updateActivity(first, Map.of("bike_id", bike.getId()));
		activityService.updateActivity(second, Map.of("bike_id", bike.getId()));
		Bike othersBike = newBike(other, "Not mine", 50);
		Activity othersActivity = newActivity(other, Sport.BIKE, 50.0);
		activityService.updateActivity(othersActivity, Map.of("bike_id", othersBike.getId()));

		activityService.deleteAllActivities(athlete.getId());

		// 200 + round(40.3) + round(19.6) - (round(40.3) + round(19.6)) = 200
		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(200);
		assertThat(bikeRepository.findById(othersBike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);
	}
}
