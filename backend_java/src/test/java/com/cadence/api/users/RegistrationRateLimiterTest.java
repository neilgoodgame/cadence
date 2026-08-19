package com.cadence.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cadence.api.common.config.CadenceProperties;
import com.cadence.api.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RegistrationRateLimiterTest {

	private static CadenceProperties.RateLimit limitOf(int maxAttempts, int windowMinutes) {
		return new CadenceProperties.RateLimit(maxAttempts, windowMinutes);
	}

	private static CadenceProperties propertiesWith(CadenceProperties.RateLimit rateLimit) {
		return new CadenceProperties(null, null, null, null, null, rateLimit, null);
	}

	/** Advances on demand, rather than by wall-clock time passing during the test. */
	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void allowsUpToTheConfiguredLimitThenRejects() {
		var limiter = new RegistrationRateLimiter(propertiesWith(limitOf(3, 60)), Clock.systemUTC());

		assertThatCode(() -> limiter.checkAndRecord("1.2.3.4")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkAndRecord("1.2.3.4")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkAndRecord("1.2.3.4")).doesNotThrowAnyException();

		assertThatThrownBy(() -> limiter.checkAndRecord("1.2.3.4"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}

	@Test
	void tracksEachIpIndependently() {
		var limiter = new RegistrationRateLimiter(propertiesWith(limitOf(1, 60)), Clock.systemUTC());

		assertThatCode(() -> limiter.checkAndRecord("1.1.1.1")).doesNotThrowAnyException();
		// A different IP isn't affected by 1.1.1.1 already being at its limit.
		assertThatCode(() -> limiter.checkAndRecord("2.2.2.2")).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.checkAndRecord("1.1.1.1")).isInstanceOf(ApiException.class);
	}

	@Test
	void resetsOnceTheWindowExpires() {
		MutableClock clock = new MutableClock();
		var limiter = new RegistrationRateLimiter(propertiesWith(limitOf(1, 60)), clock);

		assertThatCode(() -> limiter.checkAndRecord("5.5.5.5")).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.checkAndRecord("5.5.5.5")).isInstanceOf(ApiException.class);

		clock.advance(Duration.ofMinutes(61));

		assertThatCode(() -> limiter.checkAndRecord("5.5.5.5")).doesNotThrowAnyException();
	}
}
