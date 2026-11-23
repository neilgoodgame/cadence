package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.ActivityStreamFlagService.FlagSegment;
import com.cadence.api.activities.ActivityStreamFlagService.Kind;
import com.cadence.api.activities.ActivityStreamFlagService.StreamFlagsResult;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #540 (phase 6): mirrors backend's activities/tests/test_stream_flag_service.py -
 * ActivityStreamFlagService.detectStreamFlags, dispatching to each detector and normalizing its
 * output into one FlagSegment shape. */
class StreamFlagServiceIntegrationTest extends IntegrationTest {

	private static final Instant START = Instant.parse("2026-06-01T18:00:00Z");

	@Autowired
	private ActivityStreamFlagService streamFlagService;
	@Autowired
	private ActivitySourceService sourceService;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private UserRepository userRepository;

	private User newAthlete(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Athlete");
		user.setPassword("irrelevant-for-this-test");
		return userRepository.save(user);
	}

	private Activity newActivity(User athlete, String name, String device) {
		Activity activity = new Activity();
		activity.setAthlete(athlete);
		activity.setSport(Sport.BIKE);
		activity.setName(name);
		activity.setDevice(device);
		activity.setStartDate(START);
		return activityRepository.save(activity);
	}

	private void addRecordsWith(Activity activity, int count, java.util.function.BiConsumer<Record, Integer> customize) {
		List<Record> records = new ArrayList<>();
		for (int t = 0; t < count; t++) {
			Record record = new Record();
			record.setId(new RecordId(activity.getId(), START.plusSeconds(t)));
			record.setActivity(activity);
			record.setT(t);
			customize.accept(record, t);
			records.add(record);
		}
		recordRepository.saveAll(records);
	}

	private void addConstant(Activity activity, int count, String field, int value) {
		addRecordsWith(activity, count, (record, t) -> ActivityMergeApplyService.setRecordFieldValue(record, field, value));
	}

	@Test
	void contactLossPowerDelegatesToDropoutDetection() {
		User athlete = newAthlete("java-flags-power-dropout@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		Activity source = newActivity(athlete, "Source", "Favero Assioma");
		addRecordsWith(target, 10, (record, t) -> record.setPower(t == 4 || t == 5 ? 0 : 200));
		addConstant(source, 10, "power", 220);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(), "power",
				0, false, null);

		StreamFlagsResult result = streamFlagService.detectStreamFlags(target.getId(), "power", Kind.CONTACT_LOSS,
				attach.sourceId());

		assertThat(result.field()).isEqualTo("power");
		assertThat(result.kind()).isEqualTo(Kind.CONTACT_LOSS);
		assertThat(result.segments()).containsExactly(new FlagSegment(4, 5, true));
	}

	@Test
	void contactLossHeartrateDelegatesToImplausibleJumpDetection() {
		User athlete = newAthlete("java-flags-hr-jump@example.cc");
		Activity target = newActivity(athlete, "Target", "Epix");
		Activity source = newActivity(athlete, "Source", "Tickr");
		addRecordsWith(target, 10, (record, t) -> record.setHeartrate(t == 4 || t == 5 ? 180 : 80));
		addConstant(source, 10, "heartrate", 150);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(),
				"heartrate", 0, false, "strap");

		StreamFlagsResult result = streamFlagService.detectStreamFlags(target.getId(), "heartrate", Kind.CONTACT_LOSS,
				attach.sourceId());

		assertThat(result.segments()).containsExactly(new FlagSegment(4, 5, true));
	}

	@Test
	void contactLossRejectsAnUnsupportedField() {
		User athlete = newAthlete("java-flags-bad-field@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		addConstant(target, 10, "power", 200);

		assertThatThrownBy(
				() -> streamFlagService.detectStreamFlags(target.getId(), "distance_km", Kind.CONTACT_LOSS, null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void cadenceLockDelegatesToCadenceLockDetection() {
		User athlete = newAthlete("java-flags-cadence-lock@example.cc");
		Activity target = newActivity(athlete, "Target", "Epix");
		Activity source = newActivity(athlete, "Source", "Tickr");
		addRecordsWith(target, 60, (record, t) -> {
			record.setHeartrate(t % 2 == 0 ? 152 : 148);
			record.setCadence(t % 2 == 0 ? 90 : 70);
		});
		addConstant(source, 60, "heartrate", 150);
		ActivitySourceService.AttachResult attach = sourceService.attachStream(target.getId(), source.getId(),
				"heartrate", 0, false, "strap");

		StreamFlagsResult result = streamFlagService.detectStreamFlags(target.getId(), "heartrate", Kind.CADENCE_LOCK,
				attach.sourceId());

		assertThat(result.segments()).containsExactly(new FlagSegment(0, 59, true));
	}

	@Test
	void cadenceLockRejectsAFieldOtherThanHeartrate() {
		User athlete = newAthlete("java-flags-cadence-lock-bad-field@example.cc");
		Activity target = newActivity(athlete, "Target", "Edge 530");
		addConstant(target, 10, "power", 200);

		assertThatThrownBy(() -> streamFlagService.detectStreamFlags(target.getId(), "power", Kind.CADENCE_LOCK, null))
				.isInstanceOf(ValidationException.class);
	}
}
