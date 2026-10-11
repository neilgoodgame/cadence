package com.cadence.api.common.error;

import org.springframework.http.HttpStatus;

/** A file was readable but its content was invalid (too few GPX points, etc.). */
public class UnprocessableEntityException extends ApiException {

	public UnprocessableEntityException(String message) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, message);
	}

	public UnprocessableEntityException(String message, String param) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, message, param);
	}
}
