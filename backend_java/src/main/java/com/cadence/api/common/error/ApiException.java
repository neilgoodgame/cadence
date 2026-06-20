package com.cadence.api.common.error;

import org.springframework.http.HttpStatus;

/** Base for application-raised HTTP errors. Caught once, centrally, by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String param;

	public ApiException(HttpStatus status, String message) {
		this(status, message, null);
	}

	public ApiException(HttpStatus status, String message, String param) {
		super(message);
		this.status = status;
		this.param = param;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getParam() {
		return param;
	}
}
