package com.cadence.api.uploads;

/** Carries the {@code error_code}/{@code error_message} that end up on a failed {@link Upload}. */
public class UploadProcessingException extends RuntimeException {

	private final String code;

	public UploadProcessingException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
