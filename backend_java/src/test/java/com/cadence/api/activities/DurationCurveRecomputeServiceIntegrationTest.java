package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Backfill path for activities that never went through the upload pipeline - restoring from an
 * export being the main real case (see the class Javadoc on DurationCurveRecomputeService).
 */
class DurationCurveRecomputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private DurationCurveRecomputeService recomputeService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private DurationCurveRepository durationCurveRepository;

	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Curve Athlete");
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

	private void addRecords(Activity activity, int count, int power, int heartrate) {
		List<Record> records = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), Instant.parse("2026-01-01T07:00:00Z").plusSeconds(i)));
			record.setActivity(activity);
			record.setT(i);
			record.setPower(power);
			record.setHeartrate(heartrate);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void recomputeAllBackfillsCurvesFromStoredRecords() {
		User athlete = newAthlete("curve-recompute@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN, "Restored run");
		// >= 60 samples so the HR curve's shortest window (60s, see BestEffortWindows.
		// HR_CURVE_DURATIONS) actually produces a point - shorter than that and the HR curve
		// row is legitimately never created at all (DurationCurveCalculator.compute returns
		// no points, so writeCurve's isEmpty() check skips the save entirely).
		addRecords(activity, 70, 250, 150);

		int processed = recomputeService.recomputeAll(athlete, (current, total) -> { });

		assertThat(processed).isEqualTo(1);
		DurationCurve powerCurve = durationCurveRepository
				.findByActivityIdAndMetric(activity.getId(), DurationCurveMetric.POWER).orElseThrow();
		assertThat(powerCurve.getExtendsTo()).isEqualTo(70);
		assertThat(powerCurve.getPoints().get("5")).isEqualTo(250.0);
		DurationCurve hrCurve = durationCurveRepository
				.findByActivityIdAndMetric(activity.getId(), DurationCurveMetric.HEARTRATE).orElseThrow();
		assertThat(hrCurve.getPoints().get("60")).isEqualTo(150.0);
	}

	@Test
	void recomputeAllSkipsMultisportParents() {
		User athlete = newAthlete("curve-recompute-multisport@example.cc");
		Activity parent = newActivity(athlete, Sport.MULTISPORT, "Triathlon");
		addRecords(parent, 10, 250, 150);

		recomputeService.recomputeAll(athlete, (current, total) -> { });

		assertThat(durationCurveRepository.findByActivityIdAndMetric(parent.getId(), DurationCurveMetric.POWER)).isEmpty();
	}

	@Test
	void recomputeAllReportsProgressPerActivity() {
		User athlete = newAthlete("curve-recompute-progress@example.cc");
		Activity a = newActivity(athlete, Sport.RUN, "Run A");
		addRecords(a, 10, 200, 140);
		Activity b = newActivity(athlete, Sport.RUN, "Run B");
		addRecords(b, 10, 220, 145);

		List<String> ticks = new ArrayList<>();
		recomputeService.recomputeAll(athlete, (current, total) -> ticks.add(current + "/" + total));

		assertThat(ticks).containsExactly("1/2", "2/2");
	}
}
