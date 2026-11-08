package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.dto.AthleteUpdateRequest;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.courses.CourseSections;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Mirrors the Python backend's athletes/tests.py:test_update_with_{no_,}existing_zone_set_reports_*recompute exactly. */
class AthleteServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private AthleteService athleteService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneSetRepository zoneSetRepository;

	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	@Test
	void reportsNoRecomputeWhenNoZoneSetExistsYet() {
		User athlete = newAthlete("no-zoneset@example.cc");

		var recomputed = athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(recomputed.zonesRecomputed()).isEmpty();
	}

	@Test
	void reportsRecomputeWhenAZoneSetAlreadyExists() {
		User athlete = newAthlete("existing-zoneset@example.cc");
		ZoneSet zoneSet = new ZoneSet();
		zoneSet.setAthlete(athlete);
		zoneSet.setType(ZoneType.BIKE_POWER);
		zoneSet.setZones(ZoneService.DEFAULT_ZONES);
		zoneSetRepository.save(zoneSet);

		var recomputed = athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(recomputed.zonesRecomputed()).containsExactly(ZoneType.BIKE_POWER);
	}

	@Test
	void updatesWeightKg() {
		User athlete = newAthlete("weight@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, 71.5, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(athlete.getWeightKg()).isEqualTo(71.5);
	}

	@Test
	void updatesWorkoutMatchNamingPreferences() {
		User athlete = newAthlete("match-prefs@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, true, true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(athlete.isRenameMatchedActivities()).isTrue();
		assertThat(athlete.isAppendMatchDateToName()).isTrue();
	}

	@Test
	void leavesWorkoutMatchNamingPreferencesUntouchedWhenOmitted() {
		User athlete = newAthlete("match-prefs-omitted@example.cc");
		athlete.setRenameMatchedActivities(true);
		userRepository.save(athlete);

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, 71.5, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(athlete.isRenameMatchedActivities()).isTrue();
		assertThat(athlete.isAppendMatchDateToName()).isFalse();
	}

	@Test
	void updatesCopyMatchedWorkoutTags() {
		User athlete = newAthlete("copy-tags@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(athlete.isCopyMatchedWorkoutTags()).isTrue();
	}

	@Test
	void updatingFtpCreatesAManualLedgerEntry() {
		User athlete = newAthlete("manual-ledger@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		List<ThresholdHistory> entries = thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(
				athlete.getId(), ThresholdField.FTP);
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).getValueNumeric()).isEqualTo(280);
		assertThat(entries.get(0).getSourceActivity()).isNull();
		assertThat(entries.get(0).getEffectiveFrom()).isEqualTo(LocalDate.now());
	}

	@Test
	void resubmittingTheSameValueDoesNotDuplicateTheLedgerEntry() {
		// The Preferences form resubmits every field on every save regardless of whether it was
		// actually edited - re-saving with the athlete's existing ftp must not insert a second entry.
		User athlete = newAthlete("manual-ledger-resubmit@example.cc");
		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, 70.0, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP)).hasSize(1);
	}

	@Test
	void updatingThresholdPaceCreatesAManualLedgerEntry() {
		User athlete = newAthlete("manual-ledger-pace@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, "3:45", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		List<ThresholdHistory> entries = thresholdHistoryRepository.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(
				athlete.getId(), ThresholdField.THRESHOLD_PACE);
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).getValuePace()).isEqualTo("3:45");
		assertThat(entries.get(0).getSourceActivity()).isNull();
	}

	@Test
	void selfEditRecordsSetByAsTheAthlete() {
		User athlete = newAthlete("setby-self@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
		assertThat(entry.getSetBy().getId()).isEqualTo(athlete.getId());
	}

	@Test
	void coachEditRecordsSetByAsTheCoach() {
		User athlete = newAthlete("setby-coach-athlete@example.cc");
		User coach = newAthlete("setby-coach@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 280, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
				coach);

		ThresholdHistory entry = thresholdHistoryRepository
				.findFirstByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(athlete.getId(), ThresholdField.FTP).orElseThrow();
		assertThat(entry.getSetBy().getId()).isEqualTo(coach.getId());
	}

	@Test
	void manualThresholdEditAloneReRatesTodaysActivityWithoutAnExplicitRecomputeCall() {
		// Issue #481, part 3: a manual threshold edit used to leave every already-ingested
		// activity's intensity/tss stale until someone happened to press a recompute button.
		User athlete = newAthlete("rerate-on-edit@example.cc");
		athlete.setFtp(232);
		athlete = userRepository.save(athlete);

		int movingTime = 11916; // 3.31 h
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Today's ride");
		activity.setStartDate(Instant.now());
		activity.setMovingTime(movingTime);
		// Stale, as if originally rated against the old 232W FTP (the real case from the issue).
		activity.setIntensity(0.798);
		activity.setTss(211);
		activity = activityRepository.save(activity);
		for (int t = 0; t < movingTime; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setPower(185);
			recordRepository.save(record);
		}

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 255, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		Activity reloaded = activityRepository.findById(activity.getId()).orElseThrow();
		// 185W at a 255W FTP: IF 0.725, TSS 174 - not the stale 0.798/211 a 232W FTP gave it.
		assertThat(reloaded.getIntensity()).isCloseTo(0.725, Offset.offset(0.0005));
		assertThat(reloaded.getTss()).isEqualTo(174);
	}

	@Test
	void manualThresholdEditDoesNotOverwriteAMultisportParentsSummedTss() {
		// A multisport parent's tss is the sum of its legs, not a single-threshold rating - the
		// re-rate hook must not touch it even when its date matches the edit.
		User athlete = newAthlete("rerate-multisport-safe@example.cc");
		athlete.setFtp(232);
		athlete = userRepository.save(athlete);

		Activity parent = new Activity();
		parent.setAthlete(athlete);
		parent.setSport(Sport.MULTISPORT);
		parent.setName("Multisport today");
		parent.setStartDate(Instant.now());
		parent.setMovingTime(7200);
		parent.setTss(150);
		parent = activityRepository.save(parent);

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, 255, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(activityRepository.findById(parent.getId()).orElseThrow().getTss()).isEqualTo(150);
	}

	@Test
	void defaultShoeLimitKmDefaultsTo800() {
		User athlete = newAthlete("shoe-limit-default@example.cc");

		assertThat(athlete.getDefaultShoeLimitKm()).isEqualTo(800);
	}

	@Test
	void updatesDefaultShoeLimitKm() {
		User athlete = newAthlete("shoe-limit-update@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 1000, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		assertThat(athlete.getDefaultShoeLimitKm()).isEqualTo(1000);
	}

	@Test
	void updatesThresholdWindowDays() {
		// Issue #482: this field (and thresholdSanityPct below) had no slot on AthleteUpdateRequest
		// at all, so Jackson silently dropped every edit the frontend sent.
		User athlete = newAthlete("threshold-window-update@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 56, null, null, null, null, null, null, null, null));

		assertThat(athlete.getThresholdWindowDays()).isEqualTo(56);
	}

	@Test
	void updatesThresholdSanityPct() {
		User athlete = newAthlete("threshold-sanity-update@example.cc");

		athleteService.updateProfile(athlete,
				new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 45, null, null, null, null, null, null, null));

		assertThat(athlete.getThresholdSanityPct()).isEqualTo(45);
	}

	private static AthleteUpdateRequest sectionSettingsOnly(AthleteUpdateRequest.SectionSettingsUpdate update) {
		return new AthleteUpdateRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, update, null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	void sectionSettingsMergeAPartialUpdateIntoTheCurrentOnes() {
		User athlete = newAthlete("section-settings-merge@example.cc");

		athleteService.updateProfile(athlete, sectionSettingsOnly(new AthleteUpdateRequest.SectionSettingsUpdate(
				"bottom_up", null, 5.0, null, null, null, null, null, null)));

		CourseSections.Settings saved = userRepository.findById(athlete.getId()).orElseThrow().getCourseSectionSettings();
		assertThat(saved.algorithm()).isEqualTo("bottom_up");
		assertThat(saved.steepMinPct()).isEqualTo(5.0);
		assertThat(saved.flatMaxPct()).isEqualTo(1.0);
	}

	@Test
	void invalidSectionSettingsAreRejectedAndNothingChanges() {
		User athlete = newAthlete("section-settings-invalid@example.cc");

		assertThatThrownBy(() -> athleteService.updateProfile(athlete, sectionSettingsOnly(
				new AthleteUpdateRequest.SectionSettingsUpdate(null, 3.0, 2.5, null, null, null, null, null, null))))
				.isInstanceOf(ValidationException.class);

		assertThat(userRepository.findById(athlete.getId()).orElseThrow().getCourseSectionSettings())
				.isEqualTo(CourseSections.Settings.DEFAULTS);
	}

	@Test
	void overlapHandlingDefaultsToOffAndIsSettableThroughTheSamePatch() {
		User athlete = newAthlete("overlap-handling-patch@example.cc");
		assertThat(athlete.getOverlapHandling()).isEqualTo(OverlapHandling.OFF);

		athleteService.updateProfile(athlete, new AthleteUpdateRequest(null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, OverlapHandling.MARK, null, null, null));

		assertThat(userRepository.findById(athlete.getId()).orElseThrow().getOverlapHandling())
				.isEqualTo(OverlapHandling.MARK);
	}
}
