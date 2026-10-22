package com.cadence.api.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GarminSyncFileNameTest {

	@Test
	void garminsOwnNamesPassThroughUnchanged() {
		assertThat(GarminSyncService.safeFileName("24807559159_ACTIVITY.fit")).isEqualTo("24807559159_ACTIVITY.fit");
	}

	@Test
	void aPathIsReducedToItsLastSegment() {
		assertThat(GarminSyncService.safeFileName("some/dir/1_ACTIVITY.fit")).isEqualTo("1_ACTIVITY.fit");
		assertThat(GarminSyncService.safeFileName("some\\dir\\1_ACTIVITY.fit")).isEqualTo("1_ACTIVITY.fit");
	}

	@Test
	void parentDirectorySegmentsAreRejected() {
		assertThat(GarminSyncService.safeFileName("../../evil.fit")).isNull();
		assertThat(GarminSyncService.safeFileName("a/../b.fit")).isNull();
		assertThat(GarminSyncService.safeFileName("..\\evil.fit")).isNull();
	}

	@Test
	void unusualCharactersAreReplacedAndAnEmptyNameIsRejected() {
		assertThat(GarminSyncService.safeFileName("my act;ivity$.fit")).isEqualTo("my_act_ivity_.fit");
		assertThat(GarminSyncService.safeFileName("dir/")).isNull();
		assertThat(GarminSyncService.safeFileName(null)).isNull();
	}
}
