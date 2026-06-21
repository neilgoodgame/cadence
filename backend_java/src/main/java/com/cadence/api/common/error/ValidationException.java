package com.cadence.api.common.error;

import org.springframework.http.HttpStatus;

public class ValidationException extends ApiException {

	public ValidationException(String message, String param) {
		super(HttpStatus.BAD_REQUEST, message, param);
	}

	public ValidationException(String message) {
		this(message, null);
	}
}
