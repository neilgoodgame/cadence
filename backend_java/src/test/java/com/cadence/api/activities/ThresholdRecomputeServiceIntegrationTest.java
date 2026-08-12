package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ThresholdRecomputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ThresholdRecomputeService service;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(200);
		return userRepository.save(user);
	}

	private Activity newBikeActivityWithRecords(User athlete, Instant startDate, int power) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Legacy ride");
		activity.setStartDate(startDate);
		activity.setMovingTime(1200);
		activity.setFtpSnapshot(200);
		activity.setThresholdChecked(false);
		activity = activityRepository.save(activity);

		for (int t = 0; t < 1200; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), startDate.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			recordRepository.save(record);
		}
		return activity;
	}

	@Test
	void flagsActivitiesWhoseEffortImpliesAHigherThreshold() {
		// 300W for the full 20-minute window implies FTP = round(0.95 * 300) = 285, above the
		// 200 on record; 150W never does.
		User athlete = newAthlete("bulk-threshold-found@example.cc");
		Activity strong = newBikeActivityWithRecords(athlete, Instant.parse("2026-01-01T07:00:00Z"), 300);
		Activity weak = newBikeActivityWithRecords(athlete, Instant.parse("2026-01-02T07:00:00Z"), 150);

		ThresholdRecomputeService.Summary summary = service.recomputeAll(athlete, null, null, null, null);

		assertThat(summary.checked()).isEqualTo(2);
		assertThat(summary.flagged()).isEqualTo(1);

		Activity reloadedStrong = activityRepository.findById(strong.getId()).orElseThrow();
		Activity reloadedWeak = activityRepository.findById(weak.getId()).orElseThrow();
		assertThat(reloadedStrong.getSuggestedFtp()).isEqualTo(285);
		assertThat(reloadedStrong.isThresholdChecked()).isTrue();
		assertThat(reloadedWeak.getSuggestedFtp()).isNull();
		assertThat(reloadedWeak.isThresholdChecked()).isTrue();
	}

	@Test
	void sportFilterNarrowsCandidates() {
		User athlete = newAthlete("bulk-threshold-sport@example.cc");
		Activity bike = newBikeActivityWithRecords(athlete, Instant.parse("2026-01-01T07:00:00Z"), 300);
		Activity run = new Activity();
		run.setAthlete(athlete);
		run.setSport(Sport.RUN);
		run.setName("Run");
		run.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		run.setThresholdChecked(false);
		run = activityRepository.save(run);

		ThresholdRecomputeService.Summary summary = service.recomputeAll(athlete, Sport.BIKE, null, null, null);

		assertThat(summary.checked()).isEqualTo(1);
		Activity reloadedBike = activityRepository.findById(bike.getId()).orElseThrow();
		Activity reloadedRun = activityRepository.findById(run.getId()).orElseThrow();
		assertThat(reloadedBike.isThresholdChecked()).isTrue();
		assertThat(reloadedRun.isThresholdChecked()).isFalse(); // outside the filter - untouched
	}

	@Test
	void dateRangeNarrowsCandidates() {
		User athlete = newAthlete("bulk-threshold-date@example.cc");
		Activity old = newBikeActivityWithRecords(athlete, Instant.parse("2020-01-01T07:00:00Z"), 300);
		Activity recent = newBikeActivityWithRecords(athlete, Instant.parse("2026-01-01T07:00:00Z"), 300);

		ThresholdRecomputeService.Summary summary =
				service.recomputeAll(athlete, null, LocalDate.parse("2025-01-01"), null, null);

		assertThat(summary.checked()).isEqualTo(1);
		Activity reloadedOld = activityRepository.findById(old.getId()).orElseThrow();
		Activity reloadedRecent = activityRepository.findById(recent.getId()).orElseThrow();
		assertThat(reloadedOld.isThresholdChecked()).isFalse();
		assertThat(reloadedRecent.isThresholdChecked()).isTrue();
	}

	@Test
	void nonBikeRunSportsAreNeverCandidates() {
		User athlete = newAthlete("bulk-threshold-swim@example.cc");
		Activity swim = new Activity();
		swim.setAthlete(athlete);
		swim.setSport(Sport.SWIM);
		swim.setName("Swim");
		swim.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		swim.setThresholdChecked(false);
		swim = activityRepository.save(swim);

		ThresholdRecomputeService.Summary summary = service.recomputeAll(athlete, null, null, null, null);

		assertThat(summary.checked()).isZero();
		Activity reloaded = activityRepository.findById(swim.getId()).orElseThrow();
		assertThat(reloaded.isThresholdChecked()).isFalse();
	}

	@Test
	void doesNotTouchOtherAthletesActivities() {
		User athlete = newAthlete("bulk-threshold-mine@example.cc");
		User other = newAthlete("bulk-threshold-other@example.cc");
		Activity otherActivity = newBikeActivityWithRecords(other, Instant.parse("2026-01-01T07:00:00Z"), 300);

		ThresholdRecomputeService.Summary summary = service.recomputeAll(athlete, null, null, null, null);

		assertThat(summary.checked()).isZero();
		Activity reloaded = activityRepository.findById(otherActivity.getId()).orElseThrow();
		assertThat(reloaded.isThresholdChecked()).isFalse();
	}

	@Test
	void reportsProgressAfterEachActivity() {
		User athlete = newAthlete("bulk-threshold-progress@example.cc");
		newBikeActivityWithRecords(athlete, Instant.parse("2026-01-01T07:00:00Z"), 300);
		newBikeActivityWithRecords(athlete, Instant.parse("2026-01-02T07:00:00Z"), 300);
		newBikeActivityWithRecords(athlete, Instant.parse("2026-01-03T07:00:00Z"), 300);

		List<int[]> calls = new ArrayList<>();
		service.recomputeAll(athlete, null, null, null, (current, total) -> calls.add(new int[] {current, total}));

		assertThat(calls).hasSize(3);
		assertThat(calls.get(0)).containsExactly(1, 3);
		assertThat(calls.get(2)).containsExactly(3, 3);
	}
}
