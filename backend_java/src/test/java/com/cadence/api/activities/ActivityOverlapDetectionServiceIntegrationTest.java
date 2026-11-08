package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.ActivityOverlapDetectionService.OverlapResolution;
import com.cadence.api.athletes.OverlapHandling;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.gear.Bike;
import com.cadence.api.gear.BikeRepository;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #498's on-ingest side of overlap detection - see the service's own Javadoc for the
 * primary-selection rule (MARK) and the saved-rule/alignment-threshold decision (MERGE, issue
 * #501 phase 4). */
class ActivityOverlapDetectionServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private ActivityOverlapDetectionService detectionService;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private MergeRepository mergeRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BikeRepository bikeRepository;

	private User newUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test User " + email);
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String device, Instant startDate, int elapsedSeconds) {
		return newActivity(athlete, device, startDate, elapsedSeconds, null, 0);
	}

	private Activity newActivity(User athlete, String device, Instant startDate, int elapsedSeconds, Bike bike, double distanceKm) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName("Bike on " + startDate);
		activity.setDevice(device);
		activity.setStartDate(startDate);
		activity.setElapsedSeconds(elapsedSeconds);
		activity.setMovingTime(elapsedSeconds);
		activity.setBike(bike);
		activity.setDistanceKm(distanceKm);
		return activityRepository.save(activity);
	}

	private Bike newBike(User athlete, int distanceKm) {
		Bike bike = new Bike();
		bike.setAthlete(athlete);
		bike.setName("Kickr bike");
		bike.setDistanceKm(distanceKm);
		return bikeRepository.save(bike);
	}

	/** A genuinely correlated cadence stream for {@code activity}, starting {@code phaseOffset}
	 * seconds later than some shared reference instant - e.g. zwift at phaseOffset=0 and garmin
	 * at phaseOffset=5 produces the same real-world-shaped sine wave 5s apart, which the
	 * alignment calculator should recover as offsetSeconds=5 with a high score. */
	private void addCorrelatedCadence(Activity activity, Instant start, int count, int phaseOffset) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), start.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			record.setCadence((int) Math.round(80 + 5 * Math.sin((t + phaseOffset) / 30.0)));
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	@Test
	void offModeDoesNothingAtAll() {
		User athlete = newUser("overlap-off@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.OFF);

		assertThat(results).isEmpty();
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void flagModeReportsThePairButChangesNothing() {
		User athlete = newUser("overlap-flag@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.FLAG);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).otherActivityId()).isEqualTo(zwift.getId());
		assertThat(results.get(0).action()).isEqualTo(OverlapAction.FLAGGED);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
		assertThat(activityRepository.findById(zwift.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void mergeModeMergesAZwiftGarminPairWhenAlignmentScoreClearsTheThreshold() {
		User athlete = newUser("overlap-merge-applied@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		addCorrelatedCadence(zwift, start, 120, 0);
		addCorrelatedCadence(garmin, start.plusSeconds(5), 120, 5);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.MERGE);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).otherActivityId()).isEqualTo(zwift.getId());
		assertThat(results.get(0).action()).isEqualTo(OverlapAction.MERGED);
		assertThat(results.get(0).primaryActivityId()).isEqualTo(zwift.getId()); // zwift is the rule's target
		assertThat(results.get(0).mergeId()).isNotNull();
		assertThat(mergeRepository.findById(results.get(0).mergeId())).isPresent();
		// Default rule hides the source as a #498 duplicate too.
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(zwift.getId());
	}

	@Test
	void mergeModeSkipsLowScoreAndLeavesThePairUnresolved() {
		// No Record rows at all - the alignment calculator can't correlate anything, so the
		// score is 0.0 (well below the threshold) even though the rule's own device patterns
		// match zwift/garmin.
		User athlete = newUser("overlap-merge-low-score@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.MERGE);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).action()).isEqualTo(OverlapAction.MERGE_SKIPPED_LOW_SCORE);
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void mergeModeFlagsWhenNoRuleExistsForTheSport() {
		// No built-in default for run, and none saved here either.
		User athlete = newUser("overlap-merge-no-rule@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity a = newActivity(athlete, "Zwift", start, 3600);
		a.setSport(Sport.RUN);
		activityRepository.save(a);
		Activity b = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		b.setSport(Sport.RUN);
		Activity garmin = activityRepository.save(b);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.MERGE);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).action()).isEqualTo(OverlapAction.FLAGGED);
	}

	@Test
	void mergeModeFlagsWhenNeitherSideMatchesTheRulesDevicePatterns() {
		User athlete = newUser("overlap-merge-no-pattern-match@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Edge 530", start, 3600);
		Activity other = newActivity(athlete, "Edge 530", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(other, OverlapHandling.MERGE);

		assertThat(results).hasSize(1);
		assertThat(results.get(0).action()).isEqualTo(OverlapAction.FLAGGED);
	}

	@Test
	void markModePicksTheZwiftCopyAsPrimaryRegardlessOfWhichSideIsNewlyIngested() {
		User athlete = newUser("overlap-mark-zwift@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity zwift = newActivity(athlete, "Zwift", start, 3600);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(garmin, OverlapHandling.MARK);

		assertThat(results).hasSize(1);
		assertThat(results.get(0)).isEqualTo(new OverlapResolution(zwift.getId(), OverlapAction.MARKED, zwift.getId()));
		Activity reloadedGarmin = activityRepository.findById(garmin.getId()).orElseThrow();
		assertThat(reloadedGarmin.getPrimaryActivity()).isNotNull();
		assertThat(reloadedGarmin.getPrimaryActivity().getId()).isEqualTo(zwift.getId());
		assertThat(activityRepository.findById(zwift.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void markModeRetractsTheMarkedDuplicatesGearMileage() {
		// Issue #498: the Garmin copy's distance must not double-count on the bike's mileage
		// total alongside the Zwift copy's once it's marked as a duplicate.
		User athlete = newUser("overlap-mark-mileage@example.cc");
		Bike bike = newBike(athlete, 142);
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start, 3600, bike, 42.0);
		Activity garmin = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600, bike, 42.0);

		detectionService.detectAndResolve(garmin, OverlapHandling.MARK);

		assertThat(bikeRepository.findById(bike.getId()).orElseThrow().getDistanceKm()).isEqualTo(100);
	}

	@Test
	void markModePicksTheZwiftCopyAsPrimaryEvenWhenTheZwiftCopyIsTheNewlyIngestedOne() {
		// Order of arrival shouldn't matter - the issue is explicit that the Garmin copy often
		// arrives first (e.g. an earlier sync) with the Zwift copy imported later, and the Zwift
		// copy must still end up primary.
		User athlete = newUser("overlap-mark-zwift-arrives-second@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity garmin = newActivity(athlete, "Garmin", start, 3600);
		Activity zwift = newActivity(athlete, "Zwift", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(zwift, OverlapHandling.MARK);

		assertThat(results).hasSize(1);
		assertThat(results.get(0)).isEqualTo(new OverlapResolution(garmin.getId(), OverlapAction.MARKED, zwift.getId()));
		assertThat(activityRepository.findById(garmin.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(zwift.getId());
	}

	@Test
	void markModeWithNeitherSideFromZwiftPrefersMoreSensorChannels() {
		User athlete = newUser("overlap-mark-sensors@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity fewSensors = newActivity(athlete, "Edge 530", start, 3600);
		fewSensors.setAvgPower(200);
		activityRepository.save(fewSensors);
		Activity manySensors = newActivity(athlete, "Fenix 8", start.plusSeconds(5), 3600);
		manySensors.setAvgPower(205);
		manySensors.setAvgHr(140);
		manySensors.setAvgCadence(85);
		activityRepository.save(manySensors);

		List<OverlapResolution> results = detectionService.detectAndResolve(fewSensors, OverlapHandling.MARK);

		assertThat(results).hasSize(1);
		assertThat(results.get(0)).isEqualTo(new OverlapResolution(manySensors.getId(), OverlapAction.MARKED, manySensors.getId()));
		assertThat(activityRepository.findById(fewSensors.getId()).orElseThrow().getPrimaryActivity().getId())
				.isEqualTo(manySensors.getId());
	}

	@Test
	void markModeFallsBackToFlagWhenTheOtherSideIsAlreadyADuplicateOfAThirdActivity() {
		User athlete = newUser("overlap-mark-chain-guard@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		// Deliberately nowhere near `start` - this is a pre-existing manual link, not something
		// that needs to itself overlap anything; it must not show up as a second overlap pair
		// for `zwift` below.
		Activity thirdPrimary = newActivity(athlete, "Wahoo", Instant.parse("2026-01-01T00:00:00Z"), 3600);
		Activity garmin = newActivity(athlete, "Garmin", start, 3600);
		garmin.setPrimaryActivity(thirdPrimary);
		activityRepository.save(garmin);
		Activity zwift = newActivity(athlete, "Zwift", start.plusSeconds(5), 3600);

		List<OverlapResolution> results = detectionService.detectAndResolve(zwift, OverlapHandling.MARK);

		assertThat(results).hasSize(1);
		assertThat(results.get(0)).isEqualTo(new OverlapResolution(garmin.getId(), OverlapAction.FLAGGED, null));
		assertThat(activityRepository.findById(zwift.getId()).orElseThrow().getPrimaryActivity()).isNull();
	}

	@Test
	void multisportActivitiesAreNeverDetectedAtAll() {
		User athlete = newUser("overlap-multisport-guard@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		newActivity(athlete, "Zwift", start, 3600);
		Activity multisport = newActivity(athlete, "Garmin", start.plusSeconds(5), 3600);
		multisport.setSport(Sport.MULTISPORT);
		activityRepository.save(multisport);

		assertThat(detectionService.detectAndResolve(multisport, OverlapHandling.MARK)).isEmpty();
	}

	@Test
	void choosePrimaryIsDeterministicWhenNeitherZwiftNorSensorsDecideIt() {
		User athlete = newUser("overlap-tiebreak@example.cc");
		Instant start = Instant.parse("2026-06-01T18:00:00Z");
		Activity earlier = newActivity(athlete, "Edge 530", start, 3600);
		Activity later = newActivity(athlete, "Edge 530", start.plusSeconds(5), 3600);

		assertThat(ActivityOverlapDetectionService.choosePrimary(earlier, later)).isEqualTo(earlier);
		assertThat(ActivityOverlapDetectionService.choosePrimary(later, earlier)).isEqualTo(earlier);
	}
}
