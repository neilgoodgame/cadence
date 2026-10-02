package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** aerobic_training_effect/anaerobic_training_effect are manually PATCH-able, unlike
 * avg_air_temp/avg_humidity, with no "don't overwrite real device data" guard - see
 * ActivityService.updateActivity's own comment for why (a FIT-embedded value from a
 * non-Garmin-device source, e.g. a Zwift-originated file routed through Garmin Connect, is
 * already untrustworthy - often a meaningless 0.0 placeholder - so there's no real reading to
 * protect against a manual correction). */
class ActivityServiceTrainingEffectTest extends IntegrationTest {

	@Autowired
	private ActivityService activityService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Double aerobic, Double anaerobic) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Easy Bike");
		activity.setStartDate(Instant.parse("2026-10-01T19:55:03Z"));
		activity.setAerobicTrainingEffect(aerobic);
		activity.setAnaerobicTrainingEffect(anaerobic);
		return activityRepository.save(activity);
	}

	@Test
	void setsAerobicAndAnaerobicTrainingEffectManually() {
		User athlete = newAthlete("te-manual-set@example.cc");
		Activity activity = newActivity(athlete, null, null);

		activityService.updateActivity(activity, Map.of("aerobic_training_effect", 3.3, "anaerobic_training_effect", 0.0));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getAerobicTrainingEffect()).isEqualTo(3.3);
		assertThat(activity.getAnaerobicTrainingEffect()).isEqualTo(0.0);
		assertThat(activity.getTrainingEffectLabel()).isEqualTo("Improving");
	}

	@Test
	void overwritesAnExistingAerobicTrainingEffectValue() {
		// The exact scenario this feature exists for: a Zwift-originated FIT carries a
		// meaningless 0.0 placeholder, and the athlete has the real value from Garmin Connect's
		// own (better) cloud-computed display.
		User athlete = newAthlete("te-overwrite@example.cc");
		Activity activity = newActivity(athlete, 0.0, 0.0);

		activityService.updateActivity(activity, Map.of("aerobic_training_effect", 3.3));

		activity = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(activity.getAerobicTrainingEffect()).isEqualTo(3.3);
	}

	@Test
	void rejectsAerobicTrainingEffectOutOfRange() {
		User athlete = newAthlete("te-out-of-range@example.cc");
		Activity activity = newActivity(athlete, null, null);

		assertThatThrownBy(() -> activityService.updateActivity(activity, Map.of("aerobic_training_effect", 5.5)))
				.isInstanceOf(ValidationException.class);
	}
}
