package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityIntensityHistogram;
import com.cadence.api.activities.ActivityIntensityHistogramService;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #616: an activity excluded from threshold detection for a field is never a candidate in
 * any scan - the live window, full and tail replay, suggestions, candidate statuses, the
 * upcoming-drop near-match rule and decay resimulation - and its decay load comes from its heart
 * rate instead of its power. Mirrors the Python backend's athletes/test_threshold_exclusion.py. */
class ThresholdExclusionIntegrationTest extends IntegrationTest {

	private static final LocalDate GOOD_DAY = LocalDate.of(2026, 3, 1);
	private static final LocalDate BAD_DAY = LocalDate.of(2026, 3, 10);

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;
	@Autowired
	private ExcludedThresholdCandidateRepository excludedRepository;
	@Autowired
	private AcceptedThresholdCandidateRepository acceptedRepository;
	@Autowired
	private ThresholdHistoryCalculator calculator;
	@Autowired
	private ThresholdDecayRecomputeService decayRecomputeService;
	@Autowired
	private DecayLoadService decayLoadService;
	@Autowired
	private ActivityIntensityHistogramService histogramService;

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

	private Activity ride(User owner, LocalDate day, int power, int heartrate) {
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
			record.setHeartrate(heartrate);
			records.add(record);
		}
		recordRepository.saveAll(records);
		return activity;
	}

	/** A genuine ride implying 247 W and a bad one (an over-reading power meter) implying 304 W. */
	private Activity[] goodAndBad(User owner) {
		return new Activity[] { ride(owner, GOOD_DAY, 260, 150), ride(owner, BAD_DAY, 320, 170) };
	}

	private void exclude(User owner, Activity activity, ThresholdField field) {
		ExcludedThresholdCandidate exclusion = new ExcludedThresholdCandidate();
		exclusion.setAthlete(owner);
		exclusion.setField(field);
		exclusion.setActivity(activity);
		excludedRepository.save(exclusion);
	}

	@Test
	void currentWindowValueIgnoresAnExcludedActivity() {
		User owner = athlete("excl-616-window@example.cc");
		Activity[] rides = goodAndBad(owner);
		assertThat(calculator.currentWindowValue(owner, ThresholdField.FTP, BAD_DAY).activityId()).isEqualTo(rides[1].getId());

		exclude(owner, rides[1], ThresholdField.FTP);

		assertThat(calculator.currentWindowValue(owner, ThresholdField.FTP, BAD_DAY).activityId()).isEqualTo(rides[0].getId());
	}

	@Test
	void exclusionIsPerField() {
		User owner = athlete("excl-616-per-field@example.cc");
		Activity[] rides = goodAndBad(owner);

		exclude(owner, rides[1], ThresholdField.CRITICAL_RUN_POWER);

		assertThat(calculator.currentWindowValue(owner, ThresholdField.FTP, BAD_DAY).activityId()).isEqualTo(rides[1].getId());
	}

	@Test
	void fullAndTailReplayNeverProduceARowFromAnExcludedActivity() {
		User owner = athlete("excl-616-replay@example.cc");
		Activity[] rides = goodAndBad(owner);
		exclude(owner, rides[1], ThresholdField.FTP);

		for (List<ThresholdHistoryCalculator.ThresholdHistoryEntry> entries : List.of(
				calculator.replayFullHistory(owner, ThresholdField.FTP),
				calculator.replayFrom(owner, ThresholdField.FTP, GOOD_DAY))) {
			Set<String> sources = entries.stream()
					.map(ThresholdHistoryCalculator.ThresholdHistoryEntry::activityId)
					.collect(Collectors.toSet());
			assertThat(sources).contains(rides[0].getId()).doesNotContain(rides[1].getId());
		}
	}

	@Test
	void rejectedCandidatesOmitsAnExcludedActivity() {
		// A 10% band against a 247 W profile value rejects the 304 W ride, so it's a suggestion -
		// until it's excluded.
		User owner = athlete("excl-616-rejected@example.cc");
		owner.setThresholdSanityPct(10);
		owner.setFtp(247);
		owner = userRepository.save(owner);
		Activity[] rides = goodAndBad(owner);
		assertThat(calculator.rejectedCandidates(owner, ThresholdField.FTP, BAD_DAY))
				.extracting(ThresholdHistoryCalculator.RejectedCandidate::activityId).contains(rides[1].getId());

		exclude(owner, rides[1], ThresholdField.FTP);

		assertThat(calculator.rejectedCandidates(owner, ThresholdField.FTP, BAD_DAY))
				.extracting(ThresholdHistoryCalculator.RejectedCandidate::activityId).doesNotContain(rides[1].getId());
	}

	@Test
	void candidateStatusesReportsExcludedWithItsImpliedValue() {
		User owner = athlete("excl-616-statuses@example.cc");
		owner.setFtp(247);
		owner = userRepository.save(owner);
		Activity[] rides = goodAndBad(owner);
		exclude(owner, rides[1], ThresholdField.FTP);

		Map<String, ThresholdHistoryCalculator.CandidateStatusEntry> statuses = calculator
				.candidateStatuses(owner, ThresholdField.FTP, GOOD_DAY, BAD_DAY).stream()
				.collect(Collectors.toMap(ThresholdHistoryCalculator.CandidateStatusEntry::activityId, s -> s));

		assertThat(statuses.get(rides[1].getId()).status()).isEqualTo(ThresholdHistoryCalculator.CandidateStatus.EXCLUDED);
		assertThat(Math.round(statuses.get(rides[1].getId()).impliedValue())).isEqualTo(304);
	}

	@Test
	void exclusionWinsOverAnAcceptance() {
		User owner = athlete("excl-616-beats-accept@example.cc");
		owner.setThresholdSanityPct(10);
		owner.setFtp(247);
		owner = userRepository.save(owner);
		Activity[] rides = goodAndBad(owner);
		AcceptedThresholdCandidate accepted = new AcceptedThresholdCandidate();
		accepted.setAthlete(owner);
		accepted.setField(ThresholdField.FTP);
		accepted.setActivity(rides[1]);
		acceptedRepository.save(accepted);
		exclude(owner, rides[1], ThresholdField.FTP);

		assertThat(calculator.currentWindowValue(owner, ThresholdField.FTP, BAD_DAY).activityId()).isEqualTo(rides[0].getId());
		assertThat(calculator.candidateStatuses(owner, ThresholdField.FTP, GOOD_DAY, BAD_DAY))
				.filteredOn(s -> s.activityId().equals(rides[1].getId()))
				.extracting(ThresholdHistoryCalculator.CandidateStatusEntry::status)
				.containsExactly(ThresholdHistoryCalculator.CandidateStatus.EXCLUDED);
	}

	// A recent ride implying the current value is both the successor and a near match, which
	// suppresses the upcoming-drop warning. Excluded, it must suppress nothing.
	@Test
	void anExcludedRideNoLongerSuppressesAnUpcomingDrop() {
		User owner = athlete("excl-616-upcoming@example.cc");
		owner.setFtp(300);
		owner.setThresholdWarningDays(14);
		owner = userRepository.save(owner);
		LocalDate anchorDay = LocalDate.of(2026, 3, 1);
		ThresholdHistory manual = new ThresholdHistory();
		manual.setAthlete(owner);
		manual.setField(ThresholdField.FTP);
		manual.setValueNumeric(300);
		manual.setEffectiveFrom(anchorDay);
		manual.setCurrentFrom(anchorDay);
		manual.setKind(ThresholdHistoryKind.MANUAL);
		thresholdHistoryRepository.save(manual);
		Activity recent = ride(owner, anchorDay.plusDays(20), 316, 150); // implies ~300
		LocalDate today = anchorDay.plusDays(25);
		assertThat(calculator.upcomingDrop(owner, ThresholdField.FTP, today)).isNull();

		exclude(owner, recent, ThresholdField.FTP);

		assertThat(calculator.upcomingDrop(owner, ThresholdField.FTP, today)).isNotNull();
	}

	@Test
	void resimulationNeverAnchorsOnAnExcludedActivity() {
		User owner = athlete("excl-616-decay@example.cc");
		owner.setThresholdDecayEnabled(true);
		owner.setThresholdDecayTauDays(25);
		owner.setThresholdDecayMinPct(3);
		owner.setThresholdDecayMaxPct(12);
		owner = userRepository.save(owner);
		Activity[] rides = goodAndBad(owner);
		exclude(owner, rides[1], ThresholdField.FTP);

		decayRecomputeService.resimulateSuffix(owner, ThresholdField.FTP, GOOD_DAY, BAD_DAY.plusDays(60), null, null, true,
				false, false);

		Set<String> sources = thresholdHistoryRepository
				.findByAthleteIdAndFieldOrderByEffectiveFromDescIdDesc(owner.getId(), ThresholdField.FTP).stream()
				.filter(row -> row.getKind() == ThresholdHistoryKind.MEASURED)
				.map(row -> row.getSourceActivity().getId())
				.collect(Collectors.toSet());
		assertThat(sources).containsExactly(rides[0].getId());
	}

	@Test
	void anExcludedActivityCountsThroughItsHeartRateInTheDecayLoad() {
		User owner = athlete("excl-616-load@example.cc");
		Activity[] rides = goodAndBad(owner);
		ActivityIntensityHistogram histogram = histogramService.ensureHistogram(rides[1]);
		Map<LocalDate, Double> withPower =
				decayLoadService.loadByDayForAthlete(owner, ThresholdField.FTP, 250.0, 160.0, BAD_DAY, BAD_DAY);

		exclude(owner, rides[1], ThresholdField.FTP);
		Map<LocalDate, Double> withHr =
				decayLoadService.loadByDayForAthlete(owner, ThresholdField.FTP, 250.0, 160.0, BAD_DAY, BAD_DAY);

		double hrOnly = DecayLoad.activityLoad(null, histogram.getHeartrate(), 250.0, 160.0);
		assertThat(hrOnly).isPositive();
		assertThat(withHr.get(BAD_DAY)).isNotEqualTo(withPower.get(BAD_DAY));
		// One activity on a fresh series: the EWMA's first day is load / 42.
		assertThat(withHr.get(BAD_DAY)).isCloseTo(hrOnly / 42, org.assertj.core.data.Offset.offset(1e-9));
	}
}
