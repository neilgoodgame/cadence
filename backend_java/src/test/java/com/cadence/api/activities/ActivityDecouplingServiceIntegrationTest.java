package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ActivityDecouplingService#computeAndPersist end-to-end against real Records and
 * ThresholdHistory - mirrors the Python backend's ComputeDecouplingTests (the pure qualification
 * math itself is covered by DecouplingDurabilityCalculatorTest). */
class ActivityDecouplingServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityDecouplingService decouplingService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ActivityDurabilityRepository durabilityRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Decoupling Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, Sport sport, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(sport);
		activity.setName("Session");
		activity.setStartDate(Instant.parse("2026-06-01T07:00:00Z"));
		activity.setMovingTime(movingTime);
		return activityRepository.save(activity);
	}

	private void setThreshold(User athlete, Activity activity, ThresholdField field, int value) {
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(field);
		entry.setValueNumeric(value);
		entry.setSourceActivity(activity);
		entry.setEffectiveFrom(LocalDate.of(2026, 1, 1));
		entry.setCurrentFrom(LocalDate.of(2026, 1, 1));
		thresholdHistoryRepository.save(entry);
	}

	private void addRecords(Activity activity, int n, Integer power, Integer hr) {
		Instant start = activity.getStartDate();
		for (int t = 0; t < n; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}
	}

	@Test
	void nonBikeRunSportGetsSportReasonAndNothingComputed() {
		User athlete = newAthlete("decoupling-sport@example.cc");
		Activity activity = newActivity(athlete, Sport.SWIM, 4000);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.getDecouplingReasons()).containsExactly("sport");
		assertThat(activity.isDecouplingQualified()).isFalse();
		assertThat(activity.getDecouplingPct()).isNull();
	}

	@Test
	void noPowerStreamGetsNoPowerReason() {
		User athlete = newAthlete("decoupling-no-power@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		addRecords(activity, 4000, null, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.getDecouplingReasons()).containsExactly("no_power");
	}

	@Test
	void qualifiedSessionSplitsIntoTwoEqualHalvesAndComputesPct() {
		User athlete = newAthlete("decoupling-qualified@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4200);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);

		Instant start = activity.getStartDate();
		for (int t = 0; t < 4200; t++) {
			Integer hr = t < 600 ? 140 : (t < 600 + 1800 ? 128 : 131);
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isTrue();
		assertThat(activity.getDecouplingReasons()).isEmpty();
		assertThat(activity.getEfFirst()).isCloseTo(200.0 / 128, org.assertj.core.api.Assertions.within(0.01));
		assertThat(activity.getEfSecond()).isCloseTo(200.0 / 131, org.assertj.core.api.Assertions.within(0.01));
		assertThat(activity.getDecouplingPct()).isGreaterThan(0);
		assertThat(activity.getDecouplingHalves()).hasSize(2);
	}

	@Test
	void negativeDecouplingIsStoredUnchanged() {
		User athlete = newAthlete("decoupling-negative@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4200);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);

		Instant start = activity.getStartDate();
		for (int t = 0; t < 4200; t++) {
			Integer hr = t < 2500 ? 140 : 130;
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(hr);
			recordRepository.save(record);
		}

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isTrue();
		assertThat(activity.getDecouplingPct()).isLessThan(0);
	}

	@Test
	void hotSessionAirAndCore() {
		User athlete = newAthlete("decoupling-hot@example.cc");
		Activity activity = newActivity(athlete, Sport.RUN, 4000);
		setThreshold(athlete, activity, ThresholdField.CRITICAL_RUN_POWER, 250);

		Instant start = activity.getStartDate();
		for (int t = 0; t < 4000; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(200);
			record.setHeartrate(140);
			record.setAirTemp(28.0);
			recordRepository.save(record);
		}

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingHot()).isTrue();
		assertThat(activity.getDecouplingAvgTemp()).isCloseTo(28.0, org.assertj.core.api.Assertions.within(0.1));
	}

	@Test
	void notHotBelowBothThresholds() {
		User athlete = newAthlete("decoupling-cool@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 250);
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingHot()).isFalse();
	}

	@Test
	void reasonsAndChecksStillPopulatedWhenNotQualified() {
		User athlete = newAthlete("decoupling-not-qualified@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		setThreshold(athlete, activity, ThresholdField.FTP, 100); // IF = 200/100 = 2.0, fails
		addRecords(activity, 4000, 200, 140);

		decouplingService.computeAndPersist(activity, athlete);

		assertThat(activity.isDecouplingQualified()).isFalse();
		assertThat(activity.getDecouplingReasons()).contains("intensity");
		assertThat(activity.getDecouplingVi()).isNotNull();
		assertThat(activity.getDecouplingIf()).isNotNull();
		assertThat(activity.getSteadySeconds()).isEqualTo(3400);
		assertThat(activity.getDecouplingPct()).isNull();
		assertThat(activity.getDecouplingHalves()).isEmpty();
	}

	@Test
	void computeAndPersistWritesDurabilityRowsIdempotently() {
		User athlete = newAthlete("decoupling-durability@example.cc");
		Activity activity = newActivity(athlete, Sport.BIKE, 4000);
		addRecords(activity, 4000, 1000, 140);

		decouplingService.computeAndPersist(activity, athlete);
		int firstCount = durabilityRepository.findByActivityIdOrderByThresholdAscWindowSAsc(activity.getId()).size();
		assertThat(firstCount).isGreaterThan(0);

		// Idempotent: recomputing replaces, not duplicates.
		decouplingService.computeAndPersist(activity, athlete);
		int secondCount = durabilityRepository.findByActivityIdOrderByThresholdAscWindowSAsc(activity.getId()).size();
		assertThat(secondCount).isEqualTo(firstCount);
	}
}
