package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cadence.api.common.domain.Sport;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A brief GPS dropout mid-activity (null lat/lng on some records) is common on real outdoor activities - must not crash the default streams response. */
class StreamServiceIntegrationTest extends IntegrationTest {

	@Autowired
	private StreamService streamService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ActivityRepository activityRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Test
	void getStreamsExcludesRecordsWithNoPositionFixInsteadOfThrowing() {
		User athlete = new User();
		athlete.setEmail("gps-dropout@example.cc");
		athlete.setName("GPS Dropout Tester");
		athlete.setPassword("irrelevant-for-this-test");
		userRepository.save(athlete);

		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.RUN);
		activity.setHasGps(true);
		activity.setName("Run with a GPS dropout");
		activity.setStartDate(Instant.parse("2026-01-01T08:00:00Z"));
		activityRepository.save(activity);

		Record withFix = new Record();
		withFix.setId(new RecordId(activity.getId(), activity.getStartDate()));
		withFix.setActivity(activity);
		withFix.setT(0);
		withFix.setLat(51.5);
		withFix.setLng(-0.1);
		recordRepository.save(withFix);

		Record dropout = new Record();
		dropout.setId(new RecordId(activity.getId(), activity.getStartDate().plusSeconds(1)));
		dropout.setActivity(activity);
		dropout.setT(1);
		dropout.setLat(null);
		dropout.setLng(null);
		recordRepository.save(dropout);

		assertThatCode(() -> streamService.getStreams(activity, "latlng", "high")).doesNotThrowAnyException();

		var streams = streamService.getStreams(activity, "latlng", "high");
		@SuppressWarnings("unchecked")
		List<List<Double>> latlng = (List<List<Double>>) streams.fields().get("latlng");
		assertThat(latlng).containsExactly(List.of(51.5, -0.1));
	}
}
