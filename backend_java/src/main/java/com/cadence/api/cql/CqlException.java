package com.cadence.api.cql;

/** Raised when a {@code q} filter string is malformed. Mapped to a 400 by {@code ApiExceptionHandler}. */
public class CqlException extends RuntimeException {

	private final String param;

	public CqlException(String message) {
		this(message, "q");
	}

	public CqlException(String message, String param) {
		super(message);
		this.param = param;
	}

	public String getParam() {
		return param;
	}
}
