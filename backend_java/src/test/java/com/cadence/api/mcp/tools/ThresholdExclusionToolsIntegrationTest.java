package com.cadence.api.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.activities.Activity;
import com.cadence.api.activities.ActivityRepository;
import com.cadence.api.activities.ActivityService;
import com.cadence.api.activities.Record;
import com.cadence.api.activities.RecordId;
import com.cadence.api.activities.RecordRepository;
import com.cadence.api.athletes.ExcludedThresholdCandidateRepository;
import com.cadence.api.athletes.ThresholdExclusionService;
import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.mcp.tools.athletes.ThresholdExclusionTools;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.support.IntegrationTest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Issue #616: the MCP tools over the threshold exclusion. Unlike the REST POST, preview is the
 * default. Mirrors the Python backend's
 * athletes/test_threshold_exclusion.py::ThresholdExclusionMcpToolTests. */
class ThresholdExclusionToolsIntegrationTest extends IntegrationTest {

	private static final LocalDate GOOD_DAY = LocalDate.of(2026, 3, 1);
	private static final LocalDate BAD_DAY = LocalDate.of(2026, 3, 10);

	@Autowired
	private ThresholdExclusionTools tools;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ActivityRepository activityRepository;
	@Autowired
	private RecordRepository recordRepository;
	@Autowired
	private ExcludedThresholdCandidateRepository excludedRepository;
	@Autowired
	private ActivityService activityService;

	private record Setup(User athlete, Activity good, Activity bad) {
	}

	@AfterEach
	void clearAuthContext() {
		AuthContextHolder.clear();
	}

	private void authAs(String userId, String... scopes) {
		AuthContextHolder.set(AuthContext.self(userId, Set.of(scopes), AuthContext.CredentialKind.OAUTH2));
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

	/** A ledger built from a genuine ride implying 247 W and a bad one implying 304 W. */
	private Setup setup(String email) {
		User user = new User();
		user.setEmail(email);
		user.setName("Exclusion Athlete");
		user.setPassword("irrelevant-for-this-test");
		user.setThresholdWindowDays(30);
		user.setThresholdSanityPct(50);
		user.setLthr(160);
		User owner = userRepository.save(user);
		Activity good = ride(owner, GOOD_DAY, 260);
		Activity bad = ride(owner, BAD_DAY, 320);
		activityService.recomputeAfterDuplicateChange(owner, EnumSet.of(ThresholdField.FTP), null);
		return new Setup(owner, good, bad);
	}

	private int ftp(User athlete) {
		return userRepository.findById(athlete.getId()).orElseThrow().getFtp();
	}

	private boolean anyExcluded(User athlete) {
		return !excludedRepository.findActivityIdsByAthleteIdAndField(athlete.getId(), ThresholdField.FTP).isEmpty();
	}

	@Test
	void excludePreviewsByDefaultAndSavesNothing() {
		Setup s = setup("exclude-mcp-preview@example.cc");
		authAs(s.athlete().getId(), "activities:read", "activities:write");

		var effect = tools.excludeThresholdCandidate("ftp", s.bad().getId(), null);

		assertThat(effect.preview()).isTrue();
		assertThat(effect.valueBefore()).isEqualTo(304);
		assertThat(effect.valueAfter()).isEqualTo(247);
		assertThat(effect.rowsRemoved()).extracting(ThresholdExclusionService.LedgerRow::sourceActivityId)
				.contains(s.bad().getId());
		assertThat(anyExcluded(s.athlete())).isFalse();
		assertThat(ftp(s.athlete())).isEqualTo(304);
	}

	@Test
	void excludeThenListThenInclude() {
		Setup s = setup("exclude-mcp-roundtrip@example.cc");
		authAs(s.athlete().getId(), "activities:read", "activities:write");

		var effect = tools.excludeThresholdCandidate("ftp", s.bad().getId(), false);

		assertThat(effect.preview()).isFalse();
		assertThat(ftp(s.athlete())).isEqualTo(247);
		var listed = tools.listThresholdExclusions(null, null);
		assertThat(listed).extracting(ThresholdExclusionService.Exclusion::activityId).containsExactly(s.bad().getId());
		assertThat(listed.get(0).field()).isEqualTo(ThresholdField.FTP);
		assertThat(listed.get(0).activityDate()).isEqualTo(BAD_DAY);
		assertThat(listed.get(0).setByUserId()).isEqualTo(s.athlete().getId());
		assertThat(tools.listThresholdExclusions("critical_run_power", null)).isEmpty();

		var preview = tools.includeThresholdCandidate("ftp", s.bad().getId(), null);
		assertThat(preview.preview()).isTrue();
		assertThat(preview.valueAfter()).isEqualTo(304);
		assertThat(anyExcluded(s.athlete())).isTrue();

		tools.includeThresholdCandidate("ftp", s.bad().getId(), false);

		assertThat(anyExcluded(s.athlete())).isFalse();
		assertThat(ftp(s.athlete())).isEqualTo(304);
	}

	@Test
	void rejectsBadInput() {
		Setup s = setup("exclude-mcp-bad-input@example.cc");
		authAs(s.athlete().getId(), "activities:read", "activities:write");
		String bad = s.bad().getId();

		assertThatThrownBy(() -> tools.excludeThresholdCandidate("weight", bad, null))
				.isInstanceOf(ValidationException.class);
		// A ride can't define a run threshold.
		assertThatThrownBy(() -> tools.excludeThresholdCandidate("threshold_pace", bad, null))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> tools.excludeThresholdCandidate("ftp", "act_missing", null))
				.isInstanceOf(NotFoundException.class);
		// Not excluded, so nothing to include.
		assertThatThrownBy(() -> tools.includeThresholdCandidate("ftp", bad, null))
				.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> tools.listThresholdExclusions("weight", null))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void changingNeedsTheWriteScopeEvenToPreview() {
		Setup s = setup("exclude-mcp-read-only@example.cc");
		authAs(s.athlete().getId(), "activities:read");

		assertThatThrownBy(() -> tools.excludeThresholdCandidate("ftp", s.bad().getId(), null))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> tools.includeThresholdCandidate("ftp", s.bad().getId(), null))
				.isInstanceOf(ForbiddenException.class);
		assertThat(tools.listThresholdExclusions(null, null)).isEmpty();
	}

	@Test
	void listingNeedsTheReadScope() {
		Setup s = setup("exclude-mcp-write-only@example.cc");
		authAs(s.athlete().getId(), "activities:write");

		assertThatThrownBy(() -> tools.listThresholdExclusions(null, null)).isInstanceOf(ForbiddenException.class);
	}
}
