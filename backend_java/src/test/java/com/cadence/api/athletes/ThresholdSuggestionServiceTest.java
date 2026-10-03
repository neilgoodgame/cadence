package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.dto.ThresholdSuggestionResponse;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** listSuggestions/accept/dismiss/undo - the caching/formatting/dismissal-keying layer
 * (ThresholdSuggestionService) on top of ThresholdSuggestionCalculatorTest's pure detection
 * methods. */
class ThresholdSuggestionServiceTest extends IntegrationTest {

	@Autowired
	private ThresholdSuggestionService service;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private AcceptedThresholdCandidateRepository acceptedRepository;
	@Autowired
	private ThresholdHistoryRepository thresholdHistoryRepository;

	private User newAthlete(String email, Integer ftp) {
		User user = new User();
		user.setEmail(email);
		user.setName("Test Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setFtp(ftp);
		return userRepository.save(user);
	}

	private Activity newPowerActivity(User owner, Instant startDate, int power, int durationSeconds) {
		Activity activity = new Activity();
		activity.setAthlete(owner);
		activity.setSport(Sport.BIKE);
		activity.setName("Ride");
		activity.setStartDate(startDate);
		activity.setMovingTime(durationSeconds);
		activity = activityRepository.save(activity);
		for (int t = 0; t < durationSeconds; t++) {
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
	void acceptRecordsARealLedgerEntryAndUpdatesTheProfile() {
		User athlete = newAthlete("threshold-sug-svc-accept@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		String suggestionId = "ftp:rejected:" + activity.getId();

		ThresholdHistory entry = service.accept(athlete, suggestionId);

		assertThat(entry).isNotNull();
		assertThat(entry.getValueNumeric()).isEqualTo(Math.round(0.95 * 500));
		athlete = userRepository.findById(athlete.getId()).orElseThrow();
		assertThat(athlete.getFtp()).isEqualTo(Math.round(0.95 * 500));
		assertThat(acceptedRepository.findByAthleteIdAndFieldAndActivityId(athlete.getId(), ThresholdField.FTP, activity.getId()))
				.isPresent();
	}

	@Test
	void acceptedNoLongerAppearsAsASuggestion() {
		User athlete = newAthlete("threshold-sug-svc-accepted-gone@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		String suggestionId = "ftp:rejected:" + activity.getId();
		service.accept(athlete, suggestionId);

		List<ThresholdSuggestionResponse> suggestions = service.listSuggestions(athlete);

		assertThat(suggestions).isEmpty();
	}

	@Test
	void undoAcceptRevertsTheOverride() {
		// A realistic prior entry, not just a bare profile value with no backing ledger row - so
		// undo has something genuine to fall back to (an athlete with no ledger history at all
		// has no reference to sanity-check against either way, matching currentWindowValue's own
		// "first-ever value" convention - this test is about the *normal* case).
		User athlete = newAthlete("threshold-sug-svc-undo@example.cc", 200);
		ThresholdHistory prior = new ThresholdHistory();
		prior.setAthlete(athlete);
		prior.setField(ThresholdField.FTP);
		prior.setValueNumeric(200);
		prior.setEffectiveFrom(LocalDate.now().minusDays(60));
		prior.setCurrentFrom(LocalDate.now().minusDays(60));
		thresholdHistoryRepository.save(prior);
		Activity activity = newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		String suggestionId = "ftp:rejected:" + activity.getId();
		service.accept(athlete, suggestionId);

		service.undoAccept(athlete, ThresholdField.FTP, activity.getId());

		assertThat(acceptedRepository.findByAthleteIdAndFieldAndActivityId(athlete.getId(), ThresholdField.FTP, activity.getId()))
				.isEmpty();
		athlete = userRepository.findById(athlete.getId()).orElseThrow();
		// Back to the prior ledger value, not left at the undone 475.
		assertThat(athlete.getFtp()).isEqualTo(200);
	}

	@Test
	void dismissRemovesItFromTheList() {
		User athlete = newAthlete("threshold-sug-svc-dismiss@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		String suggestionId = "ftp:rejected:" + activity.getId();

		boolean dismissed = service.dismiss(athlete, suggestionId);

		assertThat(dismissed).isTrue();
		assertThat(service.listSuggestions(athlete)).isEmpty();
	}

	@Test
	void undoDismissBringsItBack() {
		User athlete = newAthlete("threshold-sug-svc-undo-dismiss@example.cc", 200);
		Activity activity = newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		String suggestionId = "ftp:rejected:" + activity.getId();
		service.dismiss(athlete, suggestionId);

		service.undoDismiss(athlete, ThresholdField.FTP, ThresholdSuggestionDismissal.Kind.REJECTED, activity.getId());

		assertThat(service.listSuggestions(athlete)).hasSize(1);
	}

	@Test
	void listIsCachedUntilExplicitlyInvalidated() {
		User athlete = newAthlete("threshold-sug-svc-cache@example.cc", 200);
		assertThat(service.listSuggestions(athlete)).isEmpty();

		newPowerActivity(athlete, Instant.now().minusSeconds(5 * 86400L), 500, 1200);
		// The new activity alone doesn't bust the cache.
		assertThat(service.listSuggestions(athlete)).isEmpty();

		service.invalidate(athlete.getId());
		assertThat(service.listSuggestions(athlete)).hasSize(1);
	}

	@Test
	void dismissKeyingLetsANewOccurrenceThrough() {
		User athlete = newAthlete("threshold-sug-svc-dismiss-keying@example.cc", 250);
		int lead = ThresholdHistoryCalculator.warningLeadDays(athlete);
		// lead - 5, not lead exactly, so shifting the source by 1 day below still lands within
		// the lead window rather than crossing the boundary tested separately elsewhere.
		LocalDate effectiveFrom = LocalDate.now().minusDays(athlete.getThresholdWindowDays() + 1L - (lead - 5));
		ThresholdHistory entry = new ThresholdHistory();
		entry.setAthlete(athlete);
		entry.setField(ThresholdField.FTP);
		entry.setValueNumeric(250);
		entry.setEffectiveFrom(effectiveFrom);
		entry.setCurrentFrom(effectiveFrom);
		entry = thresholdHistoryRepository.save(entry);

		List<ThresholdSuggestionResponse> first = service.listSuggestions(athlete);
		ThresholdSuggestionResponse firstFtp =
				first.stream().filter(s -> "ftp".equals(s.field())).findFirst().orElseThrow();
		service.dismiss(athlete, firstFtp.id());

		assertThat(service.listSuggestions(athlete).stream().filter(s -> "ftp".equals(s.field()))).isEmpty();

		// The source changes (e.g. a later ingest revealed a different current entry) - a
		// genuinely new expiry date means a new dismissal key, which the old dismissal doesn't
		// cover.
		entry.setEffectiveFrom(effectiveFrom.plusDays(1));
		entry.setCurrentFrom(entry.getEffectiveFrom());
		thresholdHistoryRepository.save(entry);
		service.invalidate(athlete.getId());

		assertThat(service.listSuggestions(athlete).stream().filter(s -> "ftp".equals(s.field())).toList()).hasSize(1);
	}
}
