package com.cadence.api.races;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Golden values for the Minetti et al. (2002) cost-of-running model behind GAP - same formula
 * the Python backend's races/splits.py's _gap_pace_s_per_km must match. */
class RaceSplitsServiceTest {

	@Test
	void flatGradeLeavesPaceUnchanged() {
		assertThat(RaceSplitsService.gapPaceSPerKm(300.0, 0.0)).isEqualTo(300.0);
	}

	@Test
	void uphillGradeGivesAFasterGapThanActualPace() {
		assertThat(RaceSplitsService.gapPaceSPerKm(300.0, 10.0)).isEqualTo(181.0);
	}

	@Test
	void downhillGradeGivesASlowerGapThanActualPace() {
		assertThat(RaceSplitsService.gapPaceSPerKm(300.0, -10.0)).isEqualTo(501.9);
	}

	@Test
	void nullPaceOrGradeGivesNoGap() {
		assertThat(RaceSplitsService.gapPaceSPerKm(null, 5.0)).isNull();
		assertThat(RaceSplitsService.gapPaceSPerKm(300.0, null)).isNull();
	}
}
