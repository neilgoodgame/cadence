package com.cadence.api.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.ActivityMergeApplyService.NullPolicy;
import com.cadence.api.activities.MergeRuleService.ResolvedRule;
import com.cadence.api.activities.MergeRuleService.TargetSource;
import com.cadence.api.common.domain.Sport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-logic coverage of the device-pattern matching/assignment - the DB-backed resolve/setRule
 * side is covered via ActivityOverlapDetectionServiceIntegrationTest (automatic resolution) and
 * the get_merge_rules/set_merge_rule MCP tool tests (CRUD). Mirrors backend's
 * activities/tests/test_merge_rule_service.py - keep the two in sync. */
class MergeRuleServiceTest {

	private final MergeRuleService service = new MergeRuleService(null, null);

	private static final ResolvedRule BIKE_RULE = new ResolvedRule("device:zwift", "device:garmin",
			List.of("power"), NullPolicy.KEEP_ORIGINAL);

	private Activity activityWithDevice(String device) {
		Activity activity = new Activity();
		activity.setSport(Sport.BIKE);
		activity.setDevice(device);
		return activity;
	}

	@Test
	void matchesIsACaseInsensitivePrefixMatchOnDevice() {
		assertThat(MergeRuleService.matches(activityWithDevice("Zwift"), "device:zwift")).isTrue();
		assertThat(MergeRuleService.matches(activityWithDevice("ZWIFT - Climb Portal"), "device:zwift")).isTrue();
		assertThat(MergeRuleService.matches(activityWithDevice("Garmin Edge 830"), "device:zwift")).isFalse();
	}

	@Test
	void matchesIsFalseForANullOrBlankDevice() {
		assertThat(MergeRuleService.matches(activityWithDevice(null), "device:zwift")).isFalse();
		assertThat(MergeRuleService.matches(activityWithDevice(""), "device:zwift")).isFalse();
	}

	@Test
	void matchesRejectsAnyPatternKindOtherThanDevice() {
		assertThat(MergeRuleService.matches(activityWithDevice("Zwift"), "source:zwift")).isFalse();
		assertThat(MergeRuleService.matches(activityWithDevice("Zwift"), "zwift")).isFalse();
		assertThat(MergeRuleService.matches(activityWithDevice("Zwift"), null)).isFalse();
	}

	@Test
	void assignPicksTargetSourceRegardlessOfArgumentOrder() {
		Activity zwift = activityWithDevice("Zwift");
		Activity garmin = activityWithDevice("Garmin");

		TargetSource forward = service.assign(zwift, garmin, BIKE_RULE);
		TargetSource reversed = service.assign(garmin, zwift, BIKE_RULE);

		assertThat(forward.target()).isEqualTo(zwift);
		assertThat(forward.source()).isEqualTo(garmin);
		assertThat(reversed.target()).isEqualTo(zwift);
		assertThat(reversed.source()).isEqualTo(garmin);
	}

	@Test
	void assignIsNullWhenNeitherOrientationMatches() {
		Activity a = activityWithDevice("Edge 530");
		Activity b = activityWithDevice("Edge 530");

		assertThat(service.assign(a, b, BIKE_RULE)).isNull();
	}

	@Test
	void assignIsNullWhenBothSidesMatchTheSamePattern() {
		Activity a = activityWithDevice("Zwift");
		Activity b = activityWithDevice("Zwift - Mt Fuji");

		// Neither orientation has one side matching target and the other matching source.
		assertThat(service.assign(a, b, BIKE_RULE)).isNull();
	}
}
