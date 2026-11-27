package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.activities.TssRecomputeService;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** #621 review: the TSS re-rate is the slow step, so a preview skips it (the effect reports only
 * the ledger and live value, which it doesn't touch), and a real change re-rates only the field's
 * sport - nothing else is rated against that field's ledger. Mirrors the Python backend's
 * athletes/test_threshold_exclusion.py::ExclusionRecomputeScopeTests. */
class ThresholdExclusionRecomputeScopeIntegrationTest extends IntegrationTest {

	private static final LocalDate GOOD_DAY = LocalDate.of(2026, 3, 1);
	private static final LocalDate BAD_DAY = LocalDate.of(2026, 3, 10);

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ActivityService activityService;
	@Autowired
	private ThresholdExclusionService exclusionService;
	@MockitoSpyBean
	private TssRecomputeService tssRecomputeService;

	private User athlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Exclusion Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setThresholdWindowDays(30);
		user.setThresholdSanityPct(50);
		user.setLthr(160);
		return userRepository.save(user);
	}

	private Activity ride(User owner, LocalDate day, int power) {
		Instant start = day.atTime(7, 0).toInstant(ZoneOffset.UTC);
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(start);
		activity.setMovingTime(1200);
		activity = activityRepository.save(activity);
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < 1200; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(power);
			record.setHeartrate(150);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	/** A ledger built from a genuine ride implying 247 W and a bad one implying 304 W; returns the bad one. */
	private Activity setup(User owner) {
		ride(owner, GOOD_DAY, 260);
		Activity bad = ride(owner, BAD_DAY, 320);
		activityService.recomputeAfterDuplicateChange(owner, EnumSet.of(ThresholdField.FTP), null);
		return bad;
	}

	@Test
	void aPreviewNeverReRatesTss() {
		User owner = athlete("exclude-scope-preview@example.cc");
		Activity bad = setup(owner);

		var effect = exclusionService.exclude(owner, ThresholdField.FTP, bad.getId(), owner, true);

		assertThat(effect.valueBefore()).isEqualTo(304);
		assertThat(effect.valueAfter()).isEqualTo(247);
		verify(tssRecomputeService, never()).recomputeForAthleteFrom(any(), any());
		verify(tssRecomputeService, never()).recomputeForAthleteFrom(any(), any(), any());
	}

	@Test
	void aRealExclusionReRatesOnlyTheFieldsSport() {
		User owner = athlete("exclude-scope-real@example.cc");
		Activity bad = setup(owner);
		Activity run = new Activity();
		run.setAthlete(owner);
		run.setSport(Sport.RUN);
		run.setName("Run");
		run.setStartDate(BAD_DAY.plusDays(1).atTime(7, 0).toInstant(ZoneOffset.UTC));
		run.setMovingTime(1200);
		run.setTss(999);
		run = activityRepository.save(run);
		int badTssBefore = activityRepository.findById(bad.getId()).orElseThrow().getTss();

		exclusionService.exclude(owner, ThresholdField.FTP, bad.getId(), owner, false);

		// Now rated against 247 W, not 304 W.
		assertThat(activityRepository.findById(bad.getId()).orElseThrow().getTss()).isGreaterThan(badTssBefore);
		// A run isn't rated against ftp, so it's left alone.
		assertThat(activityRepository.findById(run.getId()).orElseThrow().getTss()).isEqualTo(999);
	}
}
