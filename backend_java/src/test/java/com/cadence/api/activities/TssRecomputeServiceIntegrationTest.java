package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** recomputeForActivity/recomputeForAthlete read the activity's own threshold snapshot, not the
 * athlete's current profile - the actual bug this snapshot feature fixes (it used to silently
 * re-rate every historical activity against the athlete's CURRENT FTP on every recompute). */
class TssRecomputeServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private TssRecomputeService tssRecomputeService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email, int ftp) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(ftp);
		return userRepository.save(user);
	}

	private Activity newRideWithConstantPower(User athlete, int ftpSnapshot, int power, int movingTime) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Old ride");
		activity.setStartDate(Instant.parse("2026-01-01T07:00:00Z"));
		activity.setMovingTime(movingTime);
		activity.setFtpSnapshot(ftpSnapshot);
		activity.setTss(0);
		activity = activityRepository.save(activity);

		for (int t = 0; t < movingTime; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), Instant.parse("2026-01-01T07:00:00Z").plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			recordRepository.save(record);
		}
		return activity;
	}

	@Test
	void recomputeForActivityUsesTheSnapshotNotTheCurrentFtp() {
		// Athlete's FTP has since gone up to 300 - the ride's own 200 snapshot should still win.
		User athlete = newAthlete("tss-recompute-single@example.cc", 300);
		Activity activity = newRideWithConstantPower(athlete, 200, 200, 3600);

		int tss = tssRecomputeService.recomputeForActivity(activity.getId());

		// 200W at a 200W snapshot FTP for a full hour = 100 TSS, not the ~44 a re-rate against
		// the athlete's current 300 FTP would silently have produced.
		assertThat(tss).isEqualTo(100);
		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		assertThat(reloaded.getTss()).isEqualTo(100);
	}

	@Test
	void recomputeForAthleteUsesEachActivitysOwnSnapshot() {
		User athlete = newAthlete("tss-recompute-bulk@example.cc", 300);
		Activity lowerFtpRide = newRideWithConstantPower(athlete, 200, 200, 3600);
		Activity higherFtpRide = newRideWithConstantPower(athlete, 300, 300, 3600);

		int updated = tssRecomputeService.recomputeForAthlete(athlete);

		assertThat(updated).isEqualTo(2);
		assertThat(activityRepository.findById(lowerFtpRide.getId()).orElseThrow().getTss()).isEqualTo(100);
		assertThat(activityRepository.findById(higherFtpRide.getId()).orElseThrow().getTss()).isEqualTo(100);
	}
}
