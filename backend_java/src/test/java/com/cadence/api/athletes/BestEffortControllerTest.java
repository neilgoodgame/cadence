package com.cadence.api.athletes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.cadence.api.activities.BestEffort;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * capPerWindow re-caps a date-filtered read to the true top N by value. Trim
 * (BestEffortComputeServiceIntegrationTest) keeps up to topN rows per window in EACH tracked
 * period independently, so a single read spanning multiple periods can otherwise return more
 * than topN rows for one window - see the doc comment on capPerWindow itself.
 */
class BestEffortControllerTest {

	private static BestEffort effort(String window, double value, long daysAgo) {
		BestEffort e = new BestEffort();
		e.setWindow(window);
		e.setValue(value);
		e.setDate(LocalDate.now().minusDays(daysAgo));
		return e;
	}

	@Test
	void capsAcrossBandsThatEachSurvivedTrimInTheirOwnPeriod() {
		// One band 200 days ago (fast - wins the wider 365-day/all-time periods) and one band
		// 100 days ago (slower, but forms its own top-N within the narrower 112-day period).
		// Both bands can survive trim independently; a read spanning both (e.g. period=1y)
		// must still cap to topN for the window, not return both bands' worth.
		List<BestEffort> efforts = List.of(
				effort("10km", 102.0, 200), effort("10km", 101.0, 200), effort("10km", 100.0, 200),
				effort("10km", 202.0, 100), effort("10km", 201.0, 100), effort("10km", 200.0, 100));

		List<BestEffort> capped = BestEffortController.capPerWindow(efforts, true, 3);

		assertThat(capped).extracting(BestEffort::getValue).containsExactly(102.0, 101.0, 100.0);
	}

	@Test
	void higherIsBetterKeepsTheLargestValues() {
		List<BestEffort> efforts = List.of(
				effort("5min", 250.0, 10), effort("5min", 300.0, 10), effort("5min", 275.0, 10), effort("5min", 260.0, 10));

		List<BestEffort> capped = BestEffortController.capPerWindow(efforts, false, 2);

		assertThat(capped).extracting(BestEffort::getValue).containsExactly(300.0, 275.0);
	}

	@Test
	void capsIndependentlyPerWindow() {
		List<BestEffort> efforts = List.of(
				effort("5km", 20.0, 10), effort("5km", 21.0, 10), effort("5km", 22.0, 10),
				effort("10km", 40.0, 10), effort("10km", 41.0, 10), effort("10km", 42.0, 10));

		List<BestEffort> capped = BestEffortController.capPerWindow(efforts, true, 2);

		assertThat(capped).extracting(BestEffort::getWindow, BestEffort::getValue)
				.containsExactly(tuple("10km", 41.0), tuple("10km", 40.0), tuple("5km", 21.0), tuple("5km", 20.0));
	}

	@Test
	void zeroTopNMeansUnlimited() {
		List<BestEffort> efforts = List.of(effort("10km", 100.0, 10), effort("10km", 101.0, 10), effort("10km", 102.0, 10));

		List<BestEffort> capped = BestEffortController.capPerWindow(efforts, true, 0);

		assertThat(capped).hasSize(3);
	}
}
