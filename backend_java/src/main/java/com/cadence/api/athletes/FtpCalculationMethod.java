package com.cadence.api.athletes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How ThresholdHistoryCalculator derives an implied FTP from a bike activity's power series.
 * TWENTY_MIN_TEST is the conventional estimate (best 20-minute power * 0.95) - practical since it
 * only needs a 20-minute qualifying effort, but assumes a fixed ~5% fade out to 60 minutes that
 * doesn't hold for every athlete's actual power-duration curve. SIXTY_MIN_DIRECT uses the best
 * 60-minute power directly (FTP's own textbook definition, no multiplier), at the cost of only
 * producing a candidate from activities long enough to have a real 60-minute window. */
public enum FtpCalculationMethod {
	TWENTY_MIN_TEST, SIXTY_MIN_DIRECT;

	@JsonValue
	public String wireValue() {
		return name().toLowerCase();
	}

	@JsonCreator
	public static FtpCalculationMethod fromWireValue(String value) {
		return FtpCalculationMethod.valueOf(value.toUpperCase());
	}
}
