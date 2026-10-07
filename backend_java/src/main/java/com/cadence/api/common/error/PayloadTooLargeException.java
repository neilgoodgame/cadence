package com.cadence.api.common.error;

import org.springframework.http.HttpStatus;

public class PayloadTooLargeException extends ApiException {

	public PayloadTooLargeException(String message) {
		super(HttpStatus.CONTENT_TOO_LARGE, message);
	}
}
