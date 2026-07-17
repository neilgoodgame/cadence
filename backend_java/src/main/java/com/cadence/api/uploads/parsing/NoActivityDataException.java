package com.cadence.api.uploads.parsing;

/**
 * A structurally valid FIT file that contains no activity data (no record messages).
 * Garmin account exports mix these metadata stubs (device info, timestamp correlation)
 * in with real activities, so batch imports treat them as skippable rather than failed.
 */
public class NoActivityDataException extends RuntimeException {

	public NoActivityDataException(String message) {
		super(message);
	}
}
