package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.dto.ActivityResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.paging.CursorPage;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's test_activity_list.py::test_filter_by_bike_id_query_param/
 * test_filter_by_shoe_id_query_param - the gear screen's "activities using this bike/shoe"
 * list. */
class ActivityGearFilterIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private BikeRepository bikeRepository;
	@Autowired
	private ShoeRepository shoeRepository;
	@Autowired
	private ShoeModelRepository shoeModelRepository;
	@Autowired
	private ShoeModelVersionRepository shoeModelVersionRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport, String name) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName(name);
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
		return shoeRepository.save(shoe);
	}

	@Test
	void filterByBikeIdParam() {
		User athlete = newUser("gear-filter-bike@example.cc");
		Bike bike = newBike(athlete, "Tarmac SL7");
		Bike otherBike = newBike(athlete, "Stumpjumper");
		Activity onTarmac = newActivity(athlete, Sport.BIKE, "On the Tarmac");
		onTarmac.setBike(bike);
		activityRepository.save(onTarmac);
		Activity onStumpy = newActivity(athlete, Sport.BIKE, "On the Stumpjumper");
		onStumpy.setBike(otherBike);
		activityRepository.save(onStumpy);

		CursorPage<ActivityResponse> page = activityService.list(
				athlete.getId(), null, null, null, null, null, null, null, 50, bike.getId(), null);

		assertThat(page.data()).extracting(ActivityResponse::name).containsExactly("On the Tarmac");
	}

	@Test
	void filterByShoeIdParam() {
		User athlete = newUser("gear-filter-shoe@example.cc");
		Shoe shoe = newShoe(athlete, "Flyers");
		Activity inFlyers = newActivity(athlete, Sport.RUN, "In the Flyers");
		inFlyers.setShoe(shoe);
		activityRepository.save(inFlyers);
		newActivity(athlete, Sport.RUN, "Barefoot");

		CursorPage<ActivityResponse> page = activityService.list(
				athlete.getId(), null, null, null, null, null, null, null, 50, null, shoe.getId());

		assertThat(page.data()).extracting(ActivityResponse::name).containsExactly("In the Flyers");
	}
}
