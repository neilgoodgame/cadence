package com.cadence.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.cadence.api.activities.BestEffortKind;
import org.junit.jupiter.api.Test;

/** Issue #597: getBestEffortTopNForKind resolves the per-kind overrides - pace defaults to
 * unlimited on its own, power defers to the flat setting until explicitly overridden, HR has
 * no override at all. No Spring context needed - pure resolution logic on a plain entity. */
class UserBestEffortTopNForKindTest {

	@Test
	void paceDefaultsToUnlimitedRegardlessOfTheFlatSetting() {
		User athlete = new User();
		athlete.setBestEffortTopN(10);

		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.RUNNING_PACE)).isZero();
	}

	@Test
	void paceOverrideIsUsedOnceSet() {
		User athlete = new User();
		athlete.setBestEffortTopN(10);
		athlete.setBestEffortTopNRunningPace(5);

		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.RUNNING_PACE)).isEqualTo(5);
	}

	@Test
	void powerDefersToTheFlatSettingUntilOverridden() {
		User athlete = new User();
		athlete.setBestEffortTopN(10);

		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.RUNNING_POWER)).isEqualTo(10);
		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.CYCLING_POWER)).isEqualTo(10);
	}

	@Test
	void powerOverrideTakesPrecedenceOnceSet() {
		User athlete = new User();
		athlete.setBestEffortTopN(10);
		athlete.setBestEffortTopNPower(0);

		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.RUNNING_POWER)).isZero();
		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.CYCLING_POWER)).isZero();
	}

	@Test
	void hrHasNoOverrideAtAll() {
		User athlete = new User();
		athlete.setBestEffortTopN(7);

		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.RUNNING_HR)).isEqualTo(7);
		assertThat(athlete.getBestEffortTopNForKind(BestEffortKind.CYCLING_HR)).isEqualTo(7);
	}
}
